package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.PhoneCandidate
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workerslots.WorkerSlot

/**
 * `26-268`/`adr/0188`: the seven-step guided ladder the mockup draws (phone → client → service → master →
 * date → slot → review, `docs/backlog/26-268-*.md` §3.4/§5.2) — a plain UI-layer enum, the identical
 * "nothing outside this screen's own composables and view model needs to know these names exist" reason
 * [BookingsTab]/[MastersDrillDownKind] already state for their own enums.
 *
 * `Date`/`Slot` used to be one combined step (author feedback 2026-09-29: "километровая простыня" — a day
 * picker and a time grid in one unbroken scroll). Split in two: [Date] picks the business-local day out of
 * the same slot read [Worker]'s own transition already fetches, [Slot] then filters that same read down to
 * the chosen day — no second network call, the identical "no second read per step" discipline this file's
 * own [ManualBookingUiState.Wizard.services]/`.workers` doc comment already states for the earlier steps.
 */
internal enum class ManualBookingStep { Phone, Client, Service, Worker, Date, Slot, Review }

/**
 * `26-268`: the phone-first recognition sub-state (`docs/backlog/26-268-*.md` §3.4's own four frames) —
 * only ever rendered while [ManualBookingUiState.Wizard.step] is [ManualBookingStep.Phone].
 */
internal sealed interface PhoneLookupState {
    /** Nothing searched yet, or the operator tapped «Изменить» to redo it. */
    data object Idle : PhoneLookupState

    data object Searching : PhoneLookupState

    /** Exactly one existing client shares this number — «Это он» reuses [candidate], «Новый клиент»
     * mints a new one anyway (the recognition is a hint, never forced — `adr/0147`). */
    data class One(
        val candidate: PhoneCandidate,
    ) : PhoneLookupState

    /** Several people share this number (a family, a shared phone) — a pick-list, plus «Новый клиент»,
     * never an automatic merge (`adr/0147`). */
    data class Many(
        val candidates: List<PhoneCandidate>,
    ) : PhoneLookupState

    /** Nobody in this tenant's own contacts has this number yet. */
    data object None : PhoneLookupState

    /** [BookingsQueueFailure] reused — the identical "is it me, or is it broken" two-way question every
     * other calendar read already answers with it. */
    data class Failed(
        val reason: BookingsQueueFailure,
    ) : PhoneLookupState
}

/** `26-268`: who the booking is for — asserted by the operator's own tap, never inferred
 * ([PhoneLookupState]'s own doc comment states why). */
internal sealed interface ManualBookingClient {
    /** A brand-new contact — [email] blank is "skipped", never invented (§3.4: "hard to justify on a phone
     * call and error-prone by ear"). */
    data class New(
        val name: String,
        val email: String,
    ) : ManualBookingClient

    /** An existing person, reused rather than re-entered — [EnterManualBookingHandler]'s own `reusePersonId`. */
    data class Existing(
        val candidate: PhoneCandidate,
    ) : ManualBookingClient
}

/**
 * `26-268`/`adr/0188`: [ManualBookingViewModel]'s whole state. [Loading]/[NotConfigured]/[Failed] cover the
 * one prefetch the flow opens on (services + the worker roster, both needed before [ManualBookingStep.Service]/
 * [ManualBookingStep.Worker] can render); [Wizard] is everything in between, one data class rather than a
 * step-shaped sealed type, because every later step still needs every earlier step's own answer to submit
 * at [ManualBookingStep.Review] — a fresh sealed arm per step would just re-carry the same accumulated
 * fields forward at each transition. [Created] is the terminal, `201`-shaped success arm the sheet's own
 * `LaunchedEffect` watches for, the identical shape [RescheduleBookingUiState.Saved]'s own doc comment
 * states for the reschedule sheet's own terminal arm.
 */
internal sealed interface ManualBookingUiState {
    data object Loading : ManualBookingUiState

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.bookings.PendingBookingsResult.NotConfigured]'s own doc comment
     * explains — unreachable in practice here (reaching this sheet at all already proved AGO Calendar is
     * configured), kept as a real, typed arm rather than a `!!`, the identical reasoning
     * [RescheduleBookingUiState.NotConfigured]'s own doc comment states. */
    data object NotConfigured : ManualBookingUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ManualBookingUiState

    data class Wizard(
        val step: ManualBookingStep,
        /** The tenant's whole active service dictionary and worker roster, fetched once at
         * [ManualBookingViewModel.open] — [ManualBookingStep.Service]/[ManualBookingStep.Worker] filter
         * these in memory rather than re-fetching per step. */
        val services: List<ConfiguredService>,
        val workers: List<Worker>,
        val phone: String = "",
        val phoneLookup: PhoneLookupState = PhoneLookupState.Idle,
        val client: ManualBookingClient? = null,
        /** [ManualBookingStep.Client]'s own new-client form fields — live only while [client] is
         * [ManualBookingClient.New] or absent; [ManualBookingClient.Existing] never reads these. */
        val newClientName: String = "",
        val newClientEmail: String = "",
        val selectedService: ConfiguredService? = null,
        val selectedWorker: Worker? = null,
        val slots: List<WorkerSlot> = emptyList(),
        val loadingSlots: Boolean = false,
        /** [ManualBookingStep.Date]'s own pick — a business-local `YYYY-MM-DD`, one of [WorkerSlot.localDate]
         * already present in [slots]. `null` until chosen; [ManualBookingStep.Slot] filters [slots] down to
         * this value rather than re-fetching, per [ManualBookingStep]'s own doc comment. */
        val selectedDate: String? = null,
        val selectedSlot: WorkerSlot? = null,
        val submitting: Boolean = false,
        // `26-268`: [BookingActionErrorUi] reused rather than a fourth near-identical two-arm type - a
        // refusal/failure on this flow's own slot read or write reduces to the exact same "server refusal,
        // shown verbatim, or something else" question [ActionErrorBanner] already renders for every other
        // write on this port.
        val actionError: BookingActionErrorUi? = null,
    ) : ManualBookingUiState

    /** `201` — the booking landed straight in `Booked`, no conversation, no veto window
     * (`docs/backlog/26-268-*.md` §3.6/§4). The sheet's own job ends here; [ManualBookingSheet]'s own
     * `LaunchedEffect` reads this once to close the sheet and hand the caller [bookingId]/[startsAt] is not
     * needed by the caller today (it re-reads the confirmed range instead, the identical "the caller
     * re-reads, the write result carries no fresh reading back" discipline
     * [ago.chat.android.core.domain.bookings.BookingActionResult.Succeeded]'s own doc comment states) —
     * carried here anyway because a terminal state that silently drops the very thing it just created would
     * be the wrong default to set for the next write this port grows. */
    data class Created(
        val bookingId: String,
        val startsAt: String,
        val endsAt: String,
    ) : ManualBookingUiState
}
