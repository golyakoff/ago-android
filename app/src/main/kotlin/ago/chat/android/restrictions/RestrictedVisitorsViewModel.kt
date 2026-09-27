package ago.chat.android.restrictions

import ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionApi
import ago.chat.android.core.domain.restrictions.VisitorRestrictionPageResult
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
 * `26-227`: «Ограниченные посетители»'s own state machine — reads and writes through
 * [VisitorRestrictionApi] alone, the `26-145`-shipped port this item's own Scope reuses rather than
 * rebuilds (`docs/design/tenant-modules-restrictions-android.md`'s own finding #4: "no new DI binding,
 * no second port for the same server resource"). The identical "loads the first page on construction"
 * shape [ago.chat.android.analytics.PhoneRevealsReportViewModel]'s own doc comment states for its
 * closest sibling screen — this view model is created only when the operator actually opens the
 * overflow's own menu item, so the first [VisitorRestrictionApi.list] call happens only then.
 */
@HiltViewModel
public class RestrictedVisitorsViewModel
    @Inject
    constructor(
        private val api: VisitorRestrictionApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<RestrictedVisitorsUiState>(RestrictedVisitorsUiState.Loading)
        public val state: StateFlow<RestrictedVisitorsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [RestrictedVisitorsUiState.Failed] screen offers —
         * the identical "asking again is the whole of retry" shape
         * [ago.chat.android.analytics.PhoneRevealsReportViewModel.refresh]'s own doc comment states.
         * Always asks for the *first* page — a stale [RestrictedVisitorsUiState.Loaded]'s own cursor is
         * discarded, never resumed from, the same "a fresh read starts fresh" posture that sibling
         * screen already takes. Also the call [liftRestriction] makes after a successful lift
         * (`docs/design/tenant-modules-restrictions-android.md` §1.4: "the VM reloads the list from the
         * top ... never an optimistic flip"). */
        public fun refresh() {
            mutableState.update { RestrictedVisitorsUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.list(before = null, limit = null) }
                mutableState.update {
                    when (result) {
                        is VisitorRestrictionPageResult.Loaded ->
                            RestrictedVisitorsUiState.Loaded(rows = result.items, nextBeforeId = result.nextBeforeId)

                        is VisitorRestrictionPageResult.Failed -> RestrictedVisitorsUiState.Failed(result.reason)
                    }
                }
            }
        }

        /**
         * The manual "load more" affordance — [RestrictedVisitorsUiState.Loaded.nextBeforeId]'s own
         * keyset cursor drives the request, the identical double-guard (cursor exhausted, or a page
         * already in flight) [ago.chat.android.analytics.PhoneRevealsReportViewModel.loadMore]'s own doc
         * comment states. Reads `current`, not the `loaded` captured at the call site, when applying the
         * answer — the identical defensive shape that sibling method's own doc comment states: if
         * [refresh] (or a successful [liftRestriction]) ran while this page was still in flight, `current`
         * is no longer [RestrictedVisitorsUiState.Loaded] by the time this completes, and the stale page
         * this call was fetching must not be spliced onto whatever replaced it.
         *
         * A failed page leaves [RestrictedVisitorsUiState.Loaded.rows]/[RestrictedVisitorsUiState.Loaded.nextBeforeId]
         * exactly as they were — no dedicated error field for this one, the identical "a secondary read
         * degrades non-destructively" posture
         * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.loadMorePastDialogs]'s own doc
         * comment states; a tap on the same "load more" control simply asks again.
         */
        public fun loadMore() {
            val loaded = mutableState.value as? RestrictedVisitorsUiState.Loaded ?: return
            val cursor = loaded.nextBeforeId ?: return
            if (loaded.loadingMore) return

            mutableState.update { loaded.copy(loadingMore = true) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.list(before = cursor, limit = null) }
                mutableState.update { current ->
                    val currentLoaded = current as? RestrictedVisitorsUiState.Loaded ?: return@update current
                    when (result) {
                        is VisitorRestrictionPageResult.Loaded ->
                            currentLoaded.copy(
                                rows = currentLoaded.rows + result.items,
                                nextBeforeId = result.nextBeforeId,
                                loadingMore = false,
                            )

                        is VisitorRestrictionPageResult.Failed -> currentLoaded.copy(loadingMore = false)
                    }
                }
            }
        }

        /**
         * Lifts one restriction, by the *visitor's* id (`VisitorRestrictionApi.lift`'s own contract —
         * `restrictionId` is only this row's own key for [RestrictedVisitorsUiState.Loaded.liftingId]'s
         * in-flight marker, never what the write is addressed by). A no-op while a different lift is
         * already [RestrictedVisitorsUiState.Loaded.liftingId] — one lift in flight across this whole
         * screen at a time, [RestrictedVisitorsUiState.Loaded.liftingId]'s own doc comment states why —
         * and unless the section is currently [RestrictedVisitorsUiState.Loaded].
         *
         * **Reload-from-the-top on success, never an optimistic in-place flip.**
         * `docs/design/tenant-modules-restrictions-android.md` §1.4 is explicit that this is the one
         * write on this screen that does not update its own row from the write's own known shape the way
         * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.toggleRestriction] does for the
         * contact panel's identical action — a lift there flips one boolean this app already knows the
         * new value of, but here the confirm dialog closes and [refresh] re-reads page one, because
         * lifting one row can also change every other active row's own position relative to a
         * newly-inserted "lifted" marker the way a plain field flip could not represent faithfully in a
         * keyset list. On [VisitorRestrictionActionResult.Refused]/[VisitorRestrictionActionResult.Failed]
         * the rows are left exactly as they were and the error surfaced as a banner — the identical
         * refused-verbatim / failed-generic split every other write in this app already draws.
         */
        public fun liftRestriction(
            restrictionId: String,
            visitorId: String,
        ) {
            val loaded = mutableState.value as? RestrictedVisitorsUiState.Loaded ?: return
            if (loaded.liftingId != null) return

            mutableState.update { loaded.copy(liftingId = restrictionId, liftError = null) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.lift(visitorId) }
                when (result) {
                    VisitorRestrictionActionResult.Succeeded -> refresh()

                    is VisitorRestrictionActionResult.Refused ->
                        mutableState.update { current ->
                            val currentLoaded = current as? RestrictedVisitorsUiState.Loaded ?: return@update current
                            currentLoaded.copy(liftingId = null, liftError = LiftRestrictionError.Refused(result.detail))
                        }

                    is VisitorRestrictionActionResult.Failed ->
                        mutableState.update { current ->
                            val currentLoaded = current as? RestrictedVisitorsUiState.Loaded ?: return@update current
                            currentLoaded.copy(liftingId = null, liftError = LiftRestrictionError.Failed(result.reason))
                        }
                }
            }
        }
    }
