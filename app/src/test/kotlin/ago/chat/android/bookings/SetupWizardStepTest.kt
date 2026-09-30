package ago.chat.android.bookings

import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.readiness.CalendarReadiness
import ago.chat.android.core.domain.readiness.PreconditionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-332`: the wizard's entire "which step am I on" decision, exercised with no Compose and no network -
 * see [SetupWizardStep]'s own doc comment for why this is a plain function in the first place. A direct port
 * of `ago-console`'s own `setupWizardStep.test.ts`: one case per place the loop over the five preconditions
 * can stop (each as the *first* unmet one, with later ones deliberately mixed so the function is proven to
 * stop at the first gap, not the last), plus the states outside that loop (the null-calendar sentinel, the
 * booking-trigger gate, and `CalendarPublished`) and the two "nothing to derive yet" inputs.
 */
class SetupWizardStepTest {
    private val fillOrder =
        listOf(
            BookingPrecondition.WorkerOnCalendar,
            BookingPrecondition.ServiceOffered,
            BookingPrecondition.WorkingHoursConfigured,
            BookingPrecondition.ScheduleSaved,
            BookingPrecondition.SlotsMaterialized,
            BookingPrecondition.CalendarPublished,
        )

    /** One calendar's own preconditions, every entry met except the ones named - the stable fill order
     * `GetBookingReadinessHandler` always returns them in. */
    private fun preconditions(unmet: Set<BookingPrecondition>): List<PreconditionState> =
        fillOrder.map { PreconditionState(it, it.name, isMet = it !in unmet) }

    private fun readinessWith(unmet: Set<BookingPrecondition>): List<CalendarReadiness> =
        listOf(
            CalendarReadiness(
                calendarId = "cal-1",
                calendarName = "Main",
                isBookable = unmet.isEmpty(),
                preconditions = preconditions(unmet),
            ),
        )

    private val nothingConfigured =
        listOf(
            CalendarReadiness(
                calendarId = null,
                calendarName = null,
                isBookable = false,
                preconditions = preconditions(fillOrder.toSet()),
            ),
        )

    @Test
    fun `null while readiness has not loaded yet`() {
        assertNull(deriveSetupWizardStep(null, hasBookingTrigger = true))
    }

    @Test
    fun `null for an empty readiness list`() {
        assertNull(deriveSetupWizardStep(emptyList(), hasBookingTrigger = true))
    }

    @Test
    fun `a tenant with no calendar at all goes to create-calendar, regardless of the trigger word`() {
        assertEquals(SetupWizardStep.CreateCalendar, deriveSetupWizardStep(nothingConfigured, hasBookingTrigger = false))
        assertEquals(SetupWizardStep.CreateCalendar, deriveSetupWizardStep(nothingConfigured, hasBookingTrigger = true))
    }

    @Test
    fun `stops at WorkerOnCalendar when it is the first unmet precondition`() {
        // Later preconditions mixed - proves the loop stops at the first gap, not the first `false` it sees.
        val readiness = readinessWith(setOf(BookingPrecondition.WorkerOnCalendar, BookingPrecondition.ScheduleSaved))
        assertEquals(SetupWizardStep.AddMaster, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `stops at ServiceOffered once WorkerOnCalendar is met`() {
        val readiness = readinessWith(setOf(BookingPrecondition.ServiceOffered, BookingPrecondition.SlotsMaterialized))
        assertEquals(SetupWizardStep.AddService, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `stops at WorkingHoursConfigured once Worker and Service are met`() {
        val readiness = readinessWith(setOf(BookingPrecondition.WorkingHoursConfigured))
        assertEquals(SetupWizardStep.WorkingHours, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `stops at ScheduleSaved once Worker, Service and Hours are met`() {
        val readiness = readinessWith(setOf(BookingPrecondition.ScheduleSaved))
        assertEquals(SetupWizardStep.ConfirmSchedule, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `stops at SlotsMaterialized once every earlier precondition is met`() {
        val readiness = readinessWith(setOf(BookingPrecondition.SlotsMaterialized))
        assertEquals(SetupWizardStep.Materializing, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `gates on the booking trigger before publish, once every readiness precondition but publish is met`() {
        val readiness = readinessWith(setOf(BookingPrecondition.CalendarPublished))
        assertEquals(SetupWizardStep.BookingTrigger, deriveSetupWizardStep(readiness, hasBookingTrigger = false))
    }

    /** Decision 6: the trigger gate applies even once the calendar is fully bookable by the server's own
     * readiness answer - "done" requires a way in, not just a met precondition list. */
    @Test
    fun `gates on the booking trigger even when every precondition, including publish, is already met`() {
        val readiness = readinessWith(emptySet())
        assertEquals(SetupWizardStep.BookingTrigger, deriveSetupWizardStep(readiness, hasBookingTrigger = false))
    }

    @Test
    fun `moves to publish once the trigger word exists and only CalendarPublished is unmet`() {
        val readiness = readinessWith(setOf(BookingPrecondition.CalendarPublished))
        assertEquals(SetupWizardStep.Publish, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `reaches done once every precondition is met and the trigger word exists`() {
        val readiness = readinessWith(emptySet())
        assertEquals(SetupWizardStep.Done, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `only ever reads the first calendar in the list`() {
        val readiness =
            readinessWith(emptySet()) +
                CalendarReadiness(
                    calendarId = "cal-2",
                    calendarName = "Second",
                    isBookable = false,
                    preconditions = preconditions(fillOrder.toSet()),
                )
        assertEquals(SetupWizardStep.Done, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    /** `26-332`: a precondition the server never sent (an older or newer backend) is skipped, never treated
     * as unmet - the function only stops on a precondition that is *present and* `isMet == false`, the
     * identical `find(...) !== undefined && !isMet` guard `ago-console`'s own `deriveWizardStep` uses. */
    @Test
    fun `an absent precondition is skipped, not treated as a gap`() {
        val readiness =
            listOf(
                CalendarReadiness(
                    calendarId = "cal-1",
                    calendarName = "Main",
                    isBookable = false,
                    // Only WorkerOnCalendar is present, and it is met - nothing else is reported at all.
                    preconditions = listOf(PreconditionState(BookingPrecondition.WorkerOnCalendar, "WorkerOnCalendar", true)),
                ),
            )
        // Every precondition-step gap is absent, the trigger exists, and CalendarPublished is absent too, so
        // the funnel falls all the way through to Done.
        assertEquals(SetupWizardStep.Done, deriveSetupWizardStep(readiness, hasBookingTrigger = true))
    }

    @Test
    fun `targetTab sends each step to the config screen that owns its write, and none for the two stepless states`() {
        assertEquals(BookingsTab.Calendars, SetupWizardStep.CreateCalendar.targetTab())
        assertEquals(BookingsTab.Masters, SetupWizardStep.AddMaster.targetTab())
        assertEquals(BookingsTab.Services, SetupWizardStep.AddService.targetTab())
        assertEquals(BookingsTab.Hours, SetupWizardStep.WorkingHours.targetTab())
        assertEquals(BookingsTab.Masters, SetupWizardStep.ConfirmSchedule.targetTab())
        assertEquals(BookingsTab.Masters, SetupWizardStep.Materializing.targetTab())
        assertEquals(BookingsTab.Calendars, SetupWizardStep.Publish.targetTab())
        // No screen: the app has no trigger-words write yet, and Done has nothing left to fix.
        assertNull(SetupWizardStep.BookingTrigger.targetTab())
        assertNull(SetupWizardStep.Done.targetTab())
    }
}
