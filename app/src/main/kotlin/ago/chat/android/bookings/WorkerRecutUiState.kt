package ago.chat.android.bookings

import ago.chat.android.core.domain.recut.RecutConfirmation
import ago.chat.android.core.domain.recut.RecutDay
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.recut.RecutPreview

/**
 * `26-172` (`26-155` part 4, the epic's own final part): [WorkerRecutViewModel]'s whole state for the
 * «Пересчёт» drill-down — one page, three steps, mirrored from `ago-console`'s own
 * `CalendarWorkerRecutPage.tsx`: a single flat state rather than an exclusive step-by-step sealed
 * hierarchy, because the console's own page is not step-gated either — the «Пересчитать с» field and
 * button stay visible throughout, [preview]/[confirming]/[result] are independently-optional panels
 * drawn beneath it, and an operator can preview again after a result without leaving the page. This is
 * a deliberate departure from [ago.chat.android.schedule.WorkingHoursUiState]/[WorkerScheduleUiState]'s
 * own four-arm "one fetch, one state" shape: this screen makes no fetch on open at all (there is nothing
 * to read until the operator picks a date and asks for a preview), so there is no `Loading` arm to
 * distinguish, and the "steps" are optional fields on one `Loaded`-shaped state rather than mutually
 * exclusive arms.
 *
 * **This replaces `26-170`'s own minimal, read-only re-cut hook** (a dialog surfaced inline on «График»,
 * preview-only, Q2's stopgap) — [WorkerScheduleUiState]'s own doc comment states why that hook is gone.
 */
internal sealed interface WorkerRecutUiState {
    /**
     * @param from the «Пересчитать с» date, ISO `yyyy-MM-dd` — defaults to today, or (reached from the
     *   Часы `RecutNotice`) the server's own `recutFrom` ([WorkerRecutViewModel.open]'s own doc comment).
     * @param previewing a preview request is in flight — disables the field and the «Предпросмотр»
     *   button, the identical `formBusy`-style guard every sibling write in this app already uses.
     * @param preview the last preview loaded, or `null` before the first one — non-null is what makes
     *   the day cards and the «Просмотреть и подтвердить» button appear at all.
     * @param decisions one entry per decided [ago.chat.android.core.domain.recut.RecutBooking.bookingId]
     *   — a plain `Map`, not a field on a copy of [preview] itself, because [preview] is the server's own
     *   read-only answer and the decisions are this screen's own draft over it, the identical
     *   read/draft split [WorkerScheduleUiState.Loaded.form] already keeps from
     *   [WorkerScheduleUiState.Loaded.existing].
     * @param confirming the `AlertDialog` (Q3) is open — set by «Просмотреть и подтвердить», cleared by
     *   dismissing it or by a successful/refused confirm.
     * @param busy the confirm write itself is in flight.
     * @param result the last confirm's own [RecutConfirmation] — kept until a fresh [preview] replaces
     *   it, the identical "shown until superseded, never auto-dismissed"
     *   [ago.chat.android.schedule.WorkingHoursUiState.Loaded.notice] already establishes.
     * @param error the last preview or confirm refusal/failure, rendered through the shared
     *   [ActionErrorBanner]/[BookingActionErrorUi] every sibling write in this app already uses.
     *   `recut.stale`/`recut.day_changed_concurrently` clear [preview]/[decisions] back to the input step
     *   before this is set ([WorkerRecutViewModel.confirm]'s own doc comment); every other refusal keeps
     *   [preview]/[decisions]/[confirming] exactly as they were.
     * @param revealingPersonIds which customers have a phone reveal in flight — the identical
     *   plain-instance-state shape [WorkerSlotsUiState.Loaded.revealingPersonIds] already establishes.
     */
    data class Loaded(
        val from: String,
        val previewing: Boolean = false,
        val preview: RecutPreview? = null,
        val decisions: Map<String, RecutDecision> = emptyMap(),
        val confirming: Boolean = false,
        val busy: Boolean = false,
        val result: RecutConfirmation? = null,
        val error: BookingActionErrorUi? = null,
        val revealingPersonIds: Set<String> = emptySet(),
    ) : WorkerRecutUiState

    /** The identical "this deployment does not run AGO Calendar at all" fact every sibling
     * `NotConfigured` arm in this app already states — unreachable in practice, since reaching this
     * drill-down at all already proved AGO Calendar is configured for this tenant (the roster read that
     * populated the Masters card the operator tapped through), the identical defensive-completeness
     * reasoning [WorkerSlotsUiState]'s own class doc comment gives for its own unreachable arm. */
    data object NotConfigured : WorkerRecutUiState
}

/** [ago.chat.android.core.domain.recut.RecutBooking.canDecide] bookings across every day of [preview] —
 * the set [WorkerRecutViewModel.confirm] sends a [ago.chat.android.core.domain.recut.RecutBookingDecision]
 * for, one for one, and the set [everyDecisionMade] checks against. Mirrors `ago-console`'s own
 * `decidableBookings` (`CalendarWorkerRecutPage.tsx`) verbatim. */
internal fun decidableBookings(preview: RecutPreview) = preview.days.flatMap { day -> day.bookings.filter { it.canDecide } }

/** Whether every decidable booking in [preview] has an entry in [decisions] — the enabling condition for
 * «Просмотреть и подтвердить», mirroring the console's own `everyDecisionMade`. */
internal fun everyDecisionMade(
    preview: RecutPreview,
    decisions: Map<String, RecutDecision>,
): Boolean = decidableBookings(preview).all { it.bookingId in decisions }

/** A day is left exactly as it is — never re-cut — when it holds a booking that either cannot be
 * decided at all (a recorded no-show) or was decided «Оставить». Mirrors the console's own `dayIsKept`
 * verbatim, field for field. */
internal fun dayIsKept(
    day: RecutDay,
    decisions: Map<String, RecutDecision>,
): Boolean = day.bookings.any { !it.canDecide || decisions[it.bookingId] == RecutDecision.Keep }

internal fun daysToRecut(
    preview: RecutPreview,
    decisions: Map<String, RecutDecision>,
): List<RecutDay> = preview.days.filterNot { dayIsKept(it, decisions) }

internal fun daysToSkip(
    preview: RecutPreview,
    decisions: Map<String, RecutDecision>,
): List<RecutDay> = preview.days.filter { dayIsKept(it, decisions) }

internal fun bookingsToCancel(
    preview: RecutPreview,
    decisions: Map<String, RecutDecision>,
): Int = decidableBookings(preview).count { decisions[it.bookingId] == RecutDecision.Cancel }

/** The confirm dialog's own «удалив N своб. слотов» number — the sum of [RecutDay.availableSlotsToDelete]
 * across every day that will actually be re-cut, never the days [daysToSkip] leaves untouched. */
internal fun availableSlotsToDelete(
    preview: RecutPreview,
    decisions: Map<String, RecutDecision>,
): Int = daysToRecut(preview, decisions).sumOf { it.availableSlotsToDelete }
