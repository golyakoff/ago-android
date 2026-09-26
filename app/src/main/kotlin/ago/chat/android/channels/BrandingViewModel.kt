package ago.chat.android.channels

import ago.chat.android.core.domain.branding.BrandingWriteResult
import ago.chat.android.core.domain.branding.LogoUploadResult
import ago.chat.android.core.domain.branding.SiteBrandingApi
import ago.chat.android.core.domain.branding.SiteBrandingResult
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
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.3): Каналы → Почта — reads the site's
 * brand identity once, then exposes two independent writes ([saveCompanyName], [uploadLogo]) that never
 * block or clear each other's own in-flight/error state, since [SiteBrandingApi]'s own doc comment
 * states this screen is two unrelated settings, not one full-DTO round trip the way
 * [WidgetConfigViewModel] rebuilds from a single committed [ago.chat.android.core.domain.widgetconfig.WidgetConfig].
 *
 * **A write is a no-op while its own kind is already in flight**, read off the *current* state rather
 * than a separate guard flag - the identical discipline
 * [ago.chat.android.channels.ChannelConnectViewModel]'s own doc comment states for its connect/disconnect
 * pair, applied here to two independent writes instead of one exclusive pair: [saveCompanyName] checks
 * only [BrandingUiState.Loaded.savingName] and [uploadLogo] checks only
 * [BrandingUiState.Loaded.uploading], so a name save in flight never blocks a concurrent logo upload
 * (`docs/design/tenant-channels-android.md` §3.3's own "independent state for name-save vs
 * logo-upload").
 *
 * **This screen never polls.** [refresh] is the initial load, the [BrandingUiState.Failed] retry, *and*
 * the top-bar refresh action the screen offers - the only way a `Pending` → `Ready`/`Rejected` logo
 * transition is ever observed, matching the console's own reopen-or-reload posture
 * (`docs/design/tenant-channels-android.md` §5.4's own edge case).
 */
@HiltViewModel
internal class BrandingViewModel
    @Inject
    constructor(
        private val api: SiteBrandingApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<BrandingUiState>(BrandingUiState.Loading)
        val state: StateFlow<BrandingUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, the [BrandingUiState.Failed] retry, and the screen's own top-bar refresh
         * action - see this class's own doc comment for why there is no fourth caller (a background
         * poll). */
        fun refresh() {
            mutableState.update { BrandingUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.fetch() }
                mutableState.update {
                    when (result) {
                        is SiteBrandingResult.Loaded ->
                            BrandingUiState.Loaded(
                                brandCompanyName = result.branding.brandCompanyName,
                                logoUrl = result.branding.logoUrl,
                                logoStatus = result.branding.logoStatus,
                                logoRejectionReason = result.branding.logoRejectionReason,
                            )

                        is SiteBrandingResult.Failed -> BrandingUiState.Failed(result.reason)
                    }
                }
            }
        }

        /** [name] is sent exactly as given - trimming and blank-to-`null` are the screen's own courtesy
         * decision (`docs/design/tenant-channels-android.md` §3.3), made before this is called, never
         * repeated here. */
        fun saveCompanyName(name: String?) {
            val loaded = mutableState.value as? BrandingUiState.Loaded ?: return
            if (loaded.savingName) return
            mutableState.update { loaded.copy(savingName = true, nameError = null) }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.updateCompanyName(name) }) {
                    is BrandingWriteResult.Saved ->
                        mutableState.update { current ->
                            (current as? BrandingUiState.Loaded)?.copy(
                                brandCompanyName = result.brandCompanyName,
                                savingName = false,
                                nameError = null,
                                nameSavedTick = current.nameSavedTick + 1,
                            ) ?: current
                        }

                    is BrandingWriteResult.Refused ->
                        mutableState.update { current ->
                            (current as? BrandingUiState.Loaded)?.copy(
                                savingName = false,
                                nameError = BrandingActionError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is BrandingWriteResult.Failed ->
                        mutableState.update { current ->
                            (current as? BrandingUiState.Loaded)?.copy(
                                savingName = false,
                                nameError = BrandingActionError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }

        /** [bytes]/[contentType] are already past the screen's own client-side courtesy check
         * ([LogoValidationProblem]) by the time this is called - this view model, like
         * [ago.chat.android.core.domain.branding.SiteBrandingApi], never re-validates them, and never
         * sees the `content://` `Uri` they were read from ([SiteBrandingApi]'s own doc comment on why
         * that read stays in `:app` but outside this class). */
        fun uploadLogo(
            bytes: ByteArray,
            contentType: String,
        ) {
            val loaded = mutableState.value as? BrandingUiState.Loaded ?: return
            if (loaded.uploading) return
            mutableState.update { loaded.copy(uploading = true, uploadError = null) }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.uploadLogo(bytes, contentType) }) {
                    is LogoUploadResult.Accepted ->
                        mutableState.update { current ->
                            (current as? BrandingUiState.Loaded)?.copy(
                                logoStatus = result.status,
                                // A fresh accepted upload always clears any earlier rejection reason -
                                // the previous logo's own fate no longer applies to this attempt. The
                                // eventual `logoUrl`/final status is whatever a later `refresh` reads,
                                // never guessed at here (this class's own doc comment: never a poll).
                                logoRejectionReason = null,
                                uploading = false,
                                uploadError = null,
                            ) ?: current
                        }

                    is LogoUploadResult.Refused ->
                        mutableState.update { current ->
                            (current as? BrandingUiState.Loaded)?.copy(
                                uploading = false,
                                uploadError = BrandingActionError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is LogoUploadResult.Failed ->
                        mutableState.update { current ->
                            (current as? BrandingUiState.Loaded)?.copy(
                                uploading = false,
                                uploadError = BrandingActionError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }
    }
