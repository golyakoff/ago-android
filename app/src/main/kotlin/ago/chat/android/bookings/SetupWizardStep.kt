package ago.chat.android.bookings

import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.readiness.CalendarReadiness

/**
 * `26-332` (`26-318` port of `ago-console`'s own `26-329`): the guided setup wizard's own steps. This file
 * is the one place that decides *which* step a tenant is on — a pure function of the server's own
 * `GET /api/v1/console/booking-readiness` answer ([CalendarReadiness]) plus one fact readiness does not
 * carry (whether a booking trigger word is set), and nothing else.
 *
 * **The one rule this file exists to keep: no second source of truth.** There is no local "onboarding
 * progress" store anywhere — [SetupWizardViewModel] calls [deriveSetupWizardStep] on the same readiness
 * chain [ReadinessBody] already renders on the Готовность screen, so the wizard and the readiness panel can
 * never disagree about what is left to do, and leaving the wizard and coming back simply re-derives the same
 * step from the same read (`26-318`'s own Done-when: "the wizard and the readiness panel share one source of
 * truth for 'what's missing'").
 *
 * **Why a pure function, not a composable.** [SetupWizardBody] is one `when` over the step this returns —
 * every decision about *which* branch that `when` takes lives here instead, where it can be driven with
 * plain data and asserted against every precondition combination with no Compose and no Hilt at all
 * (`docs/conventions/testing.md`: a decision with no I/O of its own is a unit test, not a UI test). This is
 * the identical "the classification/decision lives as a plain function beside the screen" shape
 * [BookingPrecondition.fixTargetTab] already establishes one file over in [ReadinessBody].
 */
internal enum class SetupWizardStep {
    CreateCalendar,
    AddMaster,
    AddService,
    WorkingHours,
    ConfirmSchedule,
    Materializing,
    BookingTrigger,
    Publish,
    Done,
}

/**
 * `GetBookingReadinessHandler.Order` (`ago-calendar`), mirrored exactly — the server's own *fill* order,
 * not [BookingPrecondition]'s enum/wire order (which lists `CalendarPublished` first). Each of these five
 * precondition keys maps onto the wizard step that fixes it. `CalendarPublished` is deliberately absent —
 * see [deriveSetupWizardStep]'s own remarks for why it is handled after this list, not inside it.
 */
private val PRECONDITION_STEPS: List<Pair<BookingPrecondition, SetupWizardStep>> =
    listOf(
        BookingPrecondition.WorkerOnCalendar to SetupWizardStep.AddMaster,
        BookingPrecondition.ServiceOffered to SetupWizardStep.AddService,
        BookingPrecondition.WorkingHoursConfigured to SetupWizardStep.WorkingHours,
        BookingPrecondition.ScheduleSaved to SetupWizardStep.ConfirmSchedule,
        BookingPrecondition.SlotsMaterialized to SetupWizardStep.Materializing,
    )

/** Every step, in the funnel order [SetupWizardBody] draws «Шаг N из M» from — the identical order the
 * enum itself is declared in, restated as a list so the index is computed from data rather than
 * [Enum.ordinal] (which would silently shift if a member were ever reordered for another reason). */
internal val setupWizardStepOrder: List<SetupWizardStep> = SetupWizardStep.entries.toList()

/**
 * `26-332`: which step a tenant is on, or `null` while the readiness read has not resolved yet (the caller
 * renders a loading state — "loading" is not a place in the funnel, so it is never a step of its own).
 *
 * **The wizard only ever walks one calendar** — `readiness[0]`, the tenant's first. v1 has no
 * multi-calendar picker (the classic screens remain the way to finish a second one), the identical "the
 * wizard is a fast path, not the only path" reasoning `ago-console`'s own `deriveWizardStep` states.
 *
 * **[hasBookingTrigger] is not a [BookingPrecondition].** The server's own readiness answer says nothing
 * about a trigger word, on purpose (`26-329` decision 6), so this is the one fact this function takes from
 * anywhere but [readiness]. It is checked *before* `CalendarPublished`: publishing with no trigger word
 * would make a calendar "bookable" by the readiness read's own definition while a visitor still has no way
 * to reach it — the widget's booking chip is the only entry point — so a trigger word is worth blocking on
 * even though `isBookable` alone would say yes.
 */
internal fun deriveSetupWizardStep(
    readiness: List<CalendarReadiness>?,
    hasBookingTrigger: Boolean,
): SetupWizardStep? {
    if (readiness.isNullOrEmpty()) {
        return null
    }

    val calendar = readiness.first()
    if (calendar.calendarId == null) {
        return SetupWizardStep.CreateCalendar
    }

    for ((precondition, step) in PRECONDITION_STEPS) {
        val state = calendar.preconditions.firstOrNull { it.precondition == precondition }
        if (state != null && !state.isMet) {
            return step
        }
    }

    if (!hasBookingTrigger) {
        return SetupWizardStep.BookingTrigger
    }

    val published = calendar.preconditions.firstOrNull { it.precondition == BookingPrecondition.CalendarPublished }
    if (published != null && !published.isMet) {
        return SetupWizardStep.Publish
    }

    return SetupWizardStep.Done
}

/**
 * `26-332`: where each step's «Открыть экран» button sends the tenant — the same `⋮` config screens the
 * hub already offers, reached through the identical [BookingsScreen] `onConfigSelected` swap the readiness
 * panel's own «Исправить» uses ([BookingPrecondition.fixTargetTab]). Kept deliberately at the top-level tab
 * granularity the readiness map already uses, so the wizard leads the tenant to exactly the screen the
 * Готовность panel would.
 *
 * `null` for the two steps with no screen to open: [SetupWizardStep.BookingTrigger] (the Android app has no
 * trigger-words write yet — [SetupWizardBody] states that gap rather than offering a dead button) and
 * [SetupWizardStep.Done] (there is nothing left to fix).
 */
internal fun SetupWizardStep.targetTab(): BookingsTab? =
    when (this) {
        SetupWizardStep.CreateCalendar -> BookingsTab.Calendars
        SetupWizardStep.AddMaster -> BookingsTab.Masters
        SetupWizardStep.AddService -> BookingsTab.Services
        SetupWizardStep.WorkingHours -> BookingsTab.Hours
        SetupWizardStep.ConfirmSchedule -> BookingsTab.Masters
        SetupWizardStep.Materializing -> BookingsTab.Masters
        SetupWizardStep.BookingTrigger -> null
        SetupWizardStep.Publish -> BookingsTab.Calendars
        SetupWizardStep.Done -> null
    }
