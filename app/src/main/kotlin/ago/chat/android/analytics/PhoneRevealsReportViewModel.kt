package ago.chat.android.analytics

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
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
 * `26-74`: «Показы телефонов»'s own state — reads [ago.chat.android.core.domain.bookings.BookingsApi]
 * directly rather than a bespoke `*ReportApi` port, the identical port every other Записи screen already
 * reads (`docs/backlog/26-74-*.md`'s own Scope item 1: "no new base URL, no new adapter class if the
 * existing one fits"). This is the one report behind Аналитика's overflow that is not built on the
 * `Ago.Chat.Api` conversation-analytics family [ConversionReportViewModel]/[TagBreakdownReportViewModel]/
 * [BookingFunnelReportViewModel] share — it is `Ago.Calendar.Api`'s own audit trail instead.
 *
 * **Loads the first page on construction**, the identical "useful the instant it opens" shape
 * [ConversionReportViewModel]'s own doc comment states for its sibling report — this view model is
 * created when the report is *opened*, not when Аналитика is
 * ([ago.chat.android.shell.AnalyticsTabHost]'s own lazy-construction shape), so the first
 * [BookingsApi.fetchPhoneReveals] call happens only once an operator actually asks for the report.
 */
@HiltViewModel
public class PhoneRevealsReportViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<PhoneRevealsReportUiState>(PhoneRevealsReportUiState.Loading)
        public val state: StateFlow<PhoneRevealsReportUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [PhoneRevealsReportUiState.Failed] screen offers —
         * the identical "asking again is the whole of retry" shape
         * [ago.chat.android.bookings.ContactsViewModel.refresh]'s own doc comment states. Always asks for
         * the *first* page — a stale [PhoneRevealsReportUiState.Loaded]'s own cursor is discarded, never
         * resumed from, the same "a fresh read starts fresh" posture [ago.chat.android.bookings.BookingsViewModel.refresh]
         * already takes. */
        public fun refresh() {
            mutableState.update { PhoneRevealsReportUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.fetchPhoneReveals(before = null, limit = null) }
                mutableState.update {
                    when (result) {
                        is PhoneRevealsResult.Loaded ->
                            PhoneRevealsReportUiState.Loaded(reveals = result.reveals, nextBefore = result.nextBefore)

                        PhoneRevealsResult.NotConfigured -> PhoneRevealsReportUiState.NotConfigured
                        is PhoneRevealsResult.Failed -> PhoneRevealsReportUiState.Failed(result.reason)
                    }
                }
            }
        }

        /**
         * `docs/backlog/26-74-*.md`'s own Scope item 5: the manual "load more" affordance —
         * [PhoneRevealsReportUiState.Loaded.nextBefore]'s own keyset cursor drives the request. A no-op
         * once the cursor is exhausted or while a page is already in flight, the same double-guard
         * [ago.chat.android.thread.ThreadViewModel.loadOlder]'s own doc comment states for a different
         * screen's own "load older" control.
         *
         * **Reads `current`, not the [loaded] captured at the call site, when applying the answer** — the
         * identical defensive shape [ago.chat.android.bookings.ContactsViewModel.reveal]'s own update
         * block takes: if [refresh] ran while this page was still in flight (an operator who tapped
         * retry, say), `current` is no longer [PhoneRevealsReportUiState.Loaded] by the time this
         * completes, and the stale page this call was fetching must not be spliced onto whatever refresh
         * has since produced.
         */
        public fun loadMore() {
            val loaded = mutableState.value as? PhoneRevealsReportUiState.Loaded ?: return
            val cursor = loaded.nextBefore ?: return
            if (loaded.loadingMore) return

            mutableState.update { loaded.copy(loadingMore = true, loadMoreFailed = null) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.fetchPhoneReveals(before = cursor, limit = null) }
                mutableState.update { current ->
                    val currentLoaded = current as? PhoneRevealsReportUiState.Loaded ?: return@update current
                    when (result) {
                        is PhoneRevealsResult.Loaded ->
                            currentLoaded.copy(
                                reveals = currentLoaded.reveals + result.reveals,
                                nextBefore = result.nextBefore,
                                loadingMore = false,
                                loadMoreFailed = null,
                            )

                        // Unreachable in practice - this method only ever runs once the first page has
                        // already proven the calendar backend configured, the same "no way to reach this
                        // arm from here" note [KtorBookingsApi.performBookingAction]'s own doc comment
                        // makes for a null base URL reaching a write. `Unexpected` is the honest "this
                        // should not be happening" answer, not a fourth arm invented for a case with no
                        // way to occur.
                        PhoneRevealsResult.NotConfigured ->
                            currentLoaded.copy(loadingMore = false, loadMoreFailed = BookingsQueueFailure.Unexpected)

                        is PhoneRevealsResult.Failed ->
                            currentLoaded.copy(loadingMore = false, loadMoreFailed = result.reason)
                    }
                }
            }
        }
    }
