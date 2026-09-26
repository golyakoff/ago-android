package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.recut.RecutApi
import ago.chat.android.core.domain.recut.RecutBookingDecision
import ago.chat.android.core.domain.recut.RecutConfirmResult
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.recut.RecutPreview
import ago.chat.android.core.domain.recut.RecutPreviewResult
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
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * `26-172` (`26-155` part 4, the epic's own final part): the «Пересчёт» drill-down's own view model,
 * over [RecutApi] (preview and confirm) and [BookingsApi] (the shared, audited phone reveal — the
 * identical [WorkerSlotsViewModel]'s own doc comment states in full for why this is not a `RecutApi`
 * method of its own). Obtained by [BookingsRoute] via `hiltViewModel()` **only inside its own drill-down
 * branch** — the identical Hilt-avoidance-when-ungated shape [WorkerScheduleViewModel]/[WorkerSlotsViewModel]'s
 * own doc comments state, and the identical `26-162` androidTest landmine.
 *
 * **Replaces `26-170`'s own read-only preview hook.** That hook lived on [WorkerScheduleViewModel]
 * and stopped at a summary count — see [WorkerScheduleUiState]'s own doc comment. This class is the
 * full three steps: preview, per-booking decisions, an `AlertDialog` confirm (Q3), and a result.
 *
 * **Always a fresh attempt on [open], never a same-worker no-op.** Unlike [WorkerScheduleViewModel.open]/
 * [WorkerSlotsViewModel.open] — which skip a same-id reopen to avoid a wasted re-fetch — this screen
 * makes no fetch on open at all, so there is nothing to save by skipping. Re-entering the drill-down
 * (from «График»'s own note+button, or a second tap on the Часы `RecutNotice` for the same worker with a
 * fresh `recutFrom`) always starts over at the input step: a stale preview/decisions left over from a
 * previous visit is a worse default than a clean slate the operator re-previews from.
 *
 * **One flat [WorkerRecutUiState.Loaded], not a step-by-step sealed hierarchy** — that type's own class
 * doc comment states why, mirroring `ago-console`'s own `CalendarWorkerRecutPage.tsx` state shape rather
 * than this app's usual "one fetch, one state" four-arm pattern.
 */
@HiltViewModel
internal class WorkerRecutViewModel
    @Inject
    constructor(
        private val api: RecutApi,
        private val bookingsApi: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow<WorkerRecutUiState>(WorkerRecutUiState.Loaded(from = today()))
        val state: StateFlow<WorkerRecutUiState> = mutableState.asStateFlow()

        /** Which worker this instance is currently showing — `null` until the first [open]. The
         * identical "a stray call before a drill-down ever opened is a safe no-op" guard
         * [WorkerScheduleViewModel.openWorkerId]'s own doc comment states. */
        private var openWorkerId: String? = null

        /**
         * Opens (or reopens) the drill-down for [workerId], resetting to a fresh input step — see this
         * class's own doc comment for why there is no same-worker skip here. [initialFrom] is the
         * server's own `recutFrom` when reached from the Часы `RecutNotice`, or `null` when reached from
         * «График»'s own note+button (defaults to today, [ago.chat.android.core.domain.workerslots.defaultWorkerSlotsRange]'s
         * own "the console's own `new Date()` is a UTC calendar date" reasoning, restated here for the
         * identical single-date need).
         */
        fun open(
            workerId: String,
            initialFrom: String?,
        ) {
            openWorkerId = workerId
            mutableState.update { WorkerRecutUiState.Loaded(from = initialFrom ?: today()) }
        }

        /** Every keystroke/pick in the «Пересчитать с» field — does not touch [WorkerRecutUiState.Loaded.preview]
         * or [WorkerRecutUiState.Loaded.result] on its own; a previous preview or result stays on screen
         * until [preview] is actually pressed again, the identical behaviour `ago-console`'s own
         * `setFrom` leaves its own preview/result state in. */
        fun onFromChanged(from: String) {
            mutableState.update { (it as? WorkerRecutUiState.Loaded)?.copy(from = from) ?: it }
        }

        /**
         * «Предпросмотр» — [ago.chat.android.core.domain.recut.RecutApi.preview]'s own doc comment
         * names the bounds refusals this can come back with. A fresh preview always clears the previous
         * one's decisions and any earlier result, mirroring `ago-console`'s own `loadPreview`
         * (`setPreview`/`setDecisions({})`/`setResult(null)`/`setConfirming(false)` together, before the
         * request even starts).
         */
        fun preview() {
            val workerId = openWorkerId ?: return
            val loaded = mutableState.value as? WorkerRecutUiState.Loaded ?: return
            if (loaded.previewing) return

            mutableState.update { loaded.copy(previewing = true, error = null, result = null, confirming = false) }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.preview(workerId, loaded.from) }) {
                    is RecutPreviewResult.Loaded ->
                        mutableState.update { current ->
                            (current as? WorkerRecutUiState.Loaded)?.copy(
                                previewing = false,
                                preview = result.preview,
                                decisions = emptyMap(),
                            ) ?: current
                        }

                    is RecutPreviewResult.Refused ->
                        mutableState.update { current ->
                            (current as? WorkerRecutUiState.Loaded)?.copy(
                                previewing = false,
                                preview = null,
                                error = BookingActionErrorUi.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is RecutPreviewResult.Failed ->
                        mutableState.update { current ->
                            (current as? WorkerRecutUiState.Loaded)?.copy(
                                previewing = false,
                                preview = null,
                                error = BookingActionErrorUi.Unavailable(result.reason),
                            ) ?: current
                        }

                    // Unreachable in practice - reaching this drill-down at all already proved AGO
                    // Calendar is configured for this tenant ([WorkerRecutUiState.NotConfigured]'s own
                    // doc comment) - kept as a real, typed arm rather than a `!!`/exception, the identical
                    // defensive completeness [WorkerScheduleViewModel]'s own former `previewRecut` gave
                    // this exact arm.
                    RecutPreviewResult.NotConfigured -> mutableState.update { WorkerRecutUiState.NotConfigured }
                }
            }
        }

        /** One `FilterChip` tap, Отменить or Оставить, for one decidable booking. */
        fun decide(
            bookingId: String,
            decision: RecutDecision,
        ) {
            mutableState.update { current ->
                (current as? WorkerRecutUiState.Loaded)?.let { it.copy(decisions = it.decisions + (bookingId to decision)) }
                    ?: current
            }
        }

        /** «Просмотреть и подтвердить» — a no-op unless every decidable booking has a decision, the
         * identical enabling condition [WorkerRecutBody] itself also checks to grey the button out (kept
         * here too, defensively, so a stray call can never open the confirm dialog with a booking still
         * undecided). Opens the `AlertDialog` (Q3); nothing is sent yet. */
        fun requestConfirm() {
            mutableState.update { current ->
                val loaded = current as? WorkerRecutUiState.Loaded ?: return@update current
                val preview = loaded.preview ?: return@update current
                if (!everyDecisionMade(preview, loaded.decisions)) return@update current
                loaded.copy(confirming = true)
            }
        }

        /** The dialog's own dismiss/Cancel — nothing was sent, so there is nothing to undo. */
        fun dismissConfirm() {
            mutableState.update { (it as? WorkerRecutUiState.Loaded)?.copy(confirming = false) ?: it }
        }

        /**
         * «Подтвердить пересчёт», the destructive call itself.
         *
         * **`recut.stale`/`recut.day_changed_concurrently` clear the preview and return to the input
         * step; every other refusal keeps it.** [RecutApi.confirm]'s own doc comment states why these two
         * codes mean the world moved under this preview's own fingerprint — the honest remedy is a fresh
         * preview, never a retry of the same decisions against a range that shifted. Every other refusal
         * (`recut.missing_decision`, `recut.invalid`, one of the bounds codes reproduced on this call) is
         * this screen's own mistake or an unrelated fact, not staleness, so the operator's decisions stay
         * exactly as typed and only the dialog closes.
         */
        fun confirm() {
            val workerId = openWorkerId ?: return
            val loaded = mutableState.value as? WorkerRecutUiState.Loaded ?: return
            val preview = loaded.preview ?: return
            if (loaded.busy) return

            val decisions =
                decidableBookings(preview).mapNotNull { booking ->
                    loaded.decisions[booking.bookingId]?.let { RecutBookingDecision(booking.bookingId, it) }
                }

            mutableState.update { loaded.copy(busy = true, error = null) }
            viewModelScope.launch {
                when (
                    val result =
                        withContext(ioDispatcher) { api.confirm(workerId, loaded.from, preview.fingerprint, decisions) }
                ) {
                    is RecutConfirmResult.Confirmed ->
                        mutableState.update { current ->
                            (current as? WorkerRecutUiState.Loaded)?.copy(
                                busy = false,
                                confirming = false,
                                preview = null,
                                decisions = emptyMap(),
                                result = result.confirmation,
                                error = null,
                            ) ?: current
                        }

                    is RecutConfirmResult.Refused ->
                        mutableState.update { current ->
                            val currentLoaded = current as? WorkerRecutUiState.Loaded ?: return@update current
                            if (result.code == CODE_STALE || result.code == CODE_DAY_CHANGED_CONCURRENTLY) {
                                currentLoaded.copy(
                                    busy = false,
                                    confirming = false,
                                    preview = null,
                                    decisions = emptyMap(),
                                    error = BookingActionErrorUi.ServerRefusal(result.detail),
                                )
                            } else {
                                currentLoaded.copy(
                                    busy = false,
                                    confirming = false,
                                    error = BookingActionErrorUi.ServerRefusal(result.detail),
                                )
                            }
                        }

                    is RecutConfirmResult.Failed ->
                        mutableState.update { current ->
                            (current as? WorkerRecutUiState.Loaded)?.copy(
                                busy = false,
                                confirming = false,
                                error = BookingActionErrorUi.Unavailable(result.reason),
                            ) ?: current
                        }

                    // Unreachable in practice - see [preview]'s own identical arm.
                    RecutConfirmResult.NotConfigured -> mutableState.update { WorkerRecutUiState.NotConfigured }
                }
            }
        }

        /**
         * Q7's accepted audit surface, [BookingRevealSurface.ANDROID_RECUT]. The identical
         * one-reveal-per-customer-at-a-time shape [WorkerSlotsViewModel.reveal]'s own doc comment states
         * in full — [replacePhoneInPreview] below is this screen's own version of that class's
         * `replacePhone`, restated for [RecutPreview]'s nested per-day shape rather than a flat list of
         * slots (the identical reason `ago-console`'s own `handleReveal` walks `preview.days` rather than
         * one flat array).
         */
        fun reveal(personId: String) {
            val loaded = mutableState.value as? WorkerRecutUiState.Loaded ?: return
            if (personId in loaded.revealingPersonIds || loaded.preview == null) return

            val revealing = loaded.revealingPersonIds + personId
            mutableState.update { loaded.copy(revealingPersonIds = revealing, error = null) }

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        bookingsApi.revealCustomerPhone(personId, BookingRevealSurface.ANDROID_RECUT)
                    }

                mutableState.update { current ->
                    val currentLoaded = current as? WorkerRecutUiState.Loaded ?: return@update current
                    val stillRevealing = currentLoaded.revealingPersonIds - personId
                    when (result) {
                        is RevealPhoneResult.Revealed ->
                            currentLoaded.copy(
                                preview = currentLoaded.preview?.let { replacePhoneInPreview(it, personId, result.phone) },
                                revealingPersonIds = stillRevealing,
                                error = null,
                            )

                        is RevealPhoneResult.Refused ->
                            currentLoaded.copy(
                                revealingPersonIds = stillRevealing,
                                error = BookingActionErrorUi.ServerRefusal(result.detail),
                            )

                        is RevealPhoneResult.Failed ->
                            currentLoaded.copy(
                                revealingPersonIds = stillRevealing,
                                error = BookingActionErrorUi.Unavailable(result.reason),
                            )
                    }
                }
            }
        }

        /** [reveal]'s own in-place unmask, across every day of the preview - the identical "match by
         * customer, not row" rule [WorkerSlotsViewModel.replacePhone]'s own doc comment states, restated
         * for [RecutPreview]'s nested shape. */
        private fun replacePhoneInPreview(
            preview: RecutPreview,
            personId: String,
            phone: String,
        ): RecutPreview =
            preview.copy(
                days =
                    preview.days.map { day ->
                        day.copy(
                            bookings =
                                day.bookings.map { booking ->
                                    if (booking.personId == personId) booking.copy(phone = phone, masked = false) else booking
                                },
                        )
                    },
            )

        private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

        private companion object {
            /** [RecutApi.confirm]'s own central refusal - see [confirm]'s own doc comment. */
            const val CODE_STALE = "recut.stale"

            /** [RecutApi.confirm]'s own narrower sibling refusal - see [confirm]'s own doc comment. */
            const val CODE_DAY_CHANGED_CONCURRENTLY = "recut.day_changed_concurrently"
        }
    }
