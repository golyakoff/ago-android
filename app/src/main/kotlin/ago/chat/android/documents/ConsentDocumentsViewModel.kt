package ago.chat.android.documents

import ago.chat.android.core.domain.consent.ConsentAcceptancesResult
import ago.chat.android.core.domain.consent.ConsentPublishResult
import ago.chat.android.core.domain.consent.ConsentPurpose
import ago.chat.android.core.domain.consent.SiteConsentDocumentsApi
import ago.chat.android.core.domain.consent.SiteConsentDocumentsResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §3): Администрирование → «Документы согласий» -
 * reads both purposes' documents once, loads each version's own "кто принял" lazily and independently,
 * and turns a publish into a courtesy-checked write followed by a fresh overview reload - the identical
 * read-then-edit shape [ago.chat.android.automation.CannedResponsesViewModel] already establishes,
 * restated here for a form with two independent sub-reads (acceptances, keyed) instead of one flat list.
 *
 * **A publish never trusts its own echo.** [publish] re-seeds [ConsentDocumentsUiState.Loaded.overview]
 * from a fresh [SiteConsentDocumentsApi.fetchOverview] call after
 * [ConsentPublishResult.Published], never from the echoed [ago.chat.android.core.domain.consent.ConsentVersion]
 * itself - "reload it, never an optimistic insert"
 * (`tenant-consent-android.md` §3.4), the identical discipline
 * [ago.chat.android.automation.CannedResponsesViewModel]'s own doc comment states for its own whole-list
 * `PUT` echo.
 */
@HiltViewModel
internal class ConsentDocumentsViewModel
    @Inject
    constructor(
        private val api: SiteConsentDocumentsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ConsentDocumentsUiState>(ConsentDocumentsUiState.Loading)
        val state: StateFlow<ConsentDocumentsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the [ConsentDocumentsUiState.Failed] retry. Always starts from
         * [ConsentDocumentsUiState.Loading] - unlike the post-publish reload in [publish], which must
         * not disturb a still-open editor's own `publishing`/`publishError` fields mid-flight. */
        fun refresh() {
            mutableState.update { ConsentDocumentsUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetchOverview() }) {
                    is SiteConsentDocumentsResult.Loaded ->
                        mutableState.update { ConsentDocumentsUiState.Loaded(overview = result.overview) }

                    is SiteConsentDocumentsResult.Failed ->
                        mutableState.update { ConsentDocumentsUiState.Failed(result.reason) }
                }
            }
        }

        /**
         * Loads one version's own "кто принял" - a no-op if [key] is already loading or loaded, so a
         * second expand of the same version never repeats the network call
         * ([ConsentDocumentsUiState.Loaded.acceptances]'s own doc comment). Fetches the *whole* kind
         * ([SiteConsentDocumentsApi.fetchAcceptances] carries every version) and filters to [AcceptanceKey.version]
         * here, mirroring `ago-console`'s own `AcceptancesList` (`tenant-consent-android.md` §1.1).
         */
        fun loadAcceptances(key: AcceptanceKey) {
            val loaded = mutableState.value as? ConsentDocumentsUiState.Loaded ?: return
            if (key in loaded.acceptances) return

            mutableState.update { current ->
                (current as? ConsentDocumentsUiState.Loaded)?.let {
                    it.copy(acceptances = it.acceptances + (key to AcceptancesUiState.Loading))
                } ?: current
            }

            viewModelScope.launch {
                val next =
                    when (val result = withContext(ioDispatcher) { api.fetchAcceptances(key.purpose) }) {
                        is ConsentAcceptancesResult.Loaded ->
                            AcceptancesUiState.Loaded(result.acceptances.filter { it.documentVersion == key.version })

                        is ConsentAcceptancesResult.Failed -> AcceptancesUiState.Failed(result.reason)
                    }
                mutableState.update { current ->
                    (current as? ConsentDocumentsUiState.Loaded)?.copy(acceptances = current.acceptances + (key to next)) ?: current
                }
            }
        }

        /** The [ConsentDocumentsUiState.Loaded.Failed]-per-expander retry - drops the cached entry so
         * [loadAcceptances] treats it as never-fetched. */
        fun retryAcceptances(key: AcceptanceKey) {
            mutableState.update { current ->
                (current as? ConsentDocumentsUiState.Loaded)?.copy(acceptances = current.acceptances - key) ?: current
            }
            loadAcceptances(key)
        }

        /**
         * Runs [validateConsentPublishDraft] first - a problem stops here as
         * [ConsentPublishActionError.Invalid], no network call made. Otherwise trims the title (the
         * body is sent as typed - [validateConsentPublishDraft]'s own doc comment states why) and calls
         * [SiteConsentDocumentsApi.publish]. A second call while one is already in flight is a no-op,
         * the identical guard [ago.chat.android.automation.CannedResponsesViewModel.save] already takes.
         */
        fun publish(
            purpose: ConsentPurpose,
            title: String,
            body: String,
        ) {
            val loaded = mutableState.value as? ConsentDocumentsUiState.Loaded ?: return
            if (loaded.publishing) return

            val problem = validateConsentPublishDraft(title, body)
            if (problem != null) {
                mutableState.update { current ->
                    (current as? ConsentDocumentsUiState.Loaded)?.copy(
                        publishError = ConsentPublishActionError.Invalid(problem),
                    ) ?: current
                }
                return
            }

            mutableState.update { current ->
                (current as? ConsentDocumentsUiState.Loaded)?.copy(publishing = true, publishError = null) ?: current
            }

            val trimmedTitle = title.trim()

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.publish(purpose, trimmedTitle, body) }) {
                    is ConsentPublishResult.Published -> reloadAfterPublish()

                    ConsentPublishResult.Conflict ->
                        mutableState.update { current ->
                            (current as? ConsentDocumentsUiState.Loaded)?.copy(
                                publishing = false,
                                publishError = ConsentPublishActionError.Conflict,
                            ) ?: current
                        }

                    is ConsentPublishResult.Refused ->
                        mutableState.update { current ->
                            (current as? ConsentDocumentsUiState.Loaded)?.copy(
                                publishing = false,
                                publishError = ConsentPublishActionError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is ConsentPublishResult.Failed ->
                        mutableState.update { current ->
                            (current as? ConsentDocumentsUiState.Loaded)?.copy(
                                publishing = false,
                                publishError = ConsentPublishActionError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }

        /** «Reload it, never an optimistic insert» (`tenant-consent-android.md` §3.4) - a fresh
         * [SiteConsentDocumentsApi.fetchOverview] after a successful publish, replacing
         * [ConsentDocumentsUiState.Loaded.acceptances] with a clean map (a just-published version's own
         * acceptances are necessarily empty, and any cached older-version entry stays correct on its own
         * key) and bumping [ConsentDocumentsUiState.Loaded.savedTick]. On a failed reload the previous
         * overview is kept on screen rather than replaced with a failure - the write itself already
         * succeeded, so "could not load" would misreport what just happened; only the spinner stops. */
        private suspend fun reloadAfterPublish() {
            val previousTick = (mutableState.value as? ConsentDocumentsUiState.Loaded)?.savedTick ?: 0
            when (val result = withContext(ioDispatcher) { api.fetchOverview() }) {
                is SiteConsentDocumentsResult.Loaded ->
                    mutableState.update {
                        ConsentDocumentsUiState.Loaded(overview = result.overview, savedTick = previousTick + 1)
                    }

                is SiteConsentDocumentsResult.Failed ->
                    mutableState.update { current ->
                        (current as? ConsentDocumentsUiState.Loaded)?.copy(publishing = false) ?: current
                    }
            }
        }
    }
