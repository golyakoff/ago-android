package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.ManualBookingResult
import ago.chat.android.core.domain.bookings.PhoneCandidate
import ago.chat.android.core.domain.bookings.PhoneCandidatesResult
import ago.chat.android.core.domain.bookings.ServicesResult
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkersApi
import ago.chat.android.core.domain.workers.WorkersResult
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.WorkerSlotStatus
import ago.chat.android.core.domain.workerslots.WorkerSlotsApi
import ago.chat.android.core.domain.workerslots.WorkerSlotsResult
import ago.chat.android.core.domain.workerslots.defaultWorkerSlotsRange
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
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
 * `26-268`/`adr/0188`: «Добавить вручную»'s own guided flow — phone-first recognition
 * ([searchPhone]/[chooseCandidate]/[chooseNewClient]), then client → service → master → date → slot →
 * review ([back] moves either direction one step at a time, state untouched), ending in [submit]'s own
 * `POST /api/v1/console/bookings/manual`
 * (`docs/backlog/26-268-*.md` §3.4/§3.6). Obtained by [ManualBookingSheet] via `hiltViewModel()` **only
 * while that sheet is open** — the identical Hilt-avoidance-when-ungated shape
 * [RescheduleBookingViewModel]'s own doc comment states, restated here for a sheet reached from every
 * segment's own header rather than one booking's own detail sheet.
 *
 * Four ports, not one: [bookingsApi] for phone recognition and the write itself, [workersApi] for the
 * worker roster (the one read that also carries [Worker.calendarId], which the write needs and
 * `BookingsApi.fetchServices` cannot supply), [workerSlotsApi] for the same-worker slot read
 * [RescheduleBookingViewModel] already reuses (`docs/backlog/26-268-*.md` §3.6: "reuse the slot-picker the
 * reschedule flow uses"), and [personsApi] for the display-merge [fetchPhoneCandidates]'s own doc comment
 * states (the calendar's own recognition read carries no name, `adr/0184`).
 *
 * **One [ManualBookingUiState.Wizard] rather than a sealed arm per step.** Every step from Client onward
 * still needs every earlier step's own answer to build [submit]'s request body, so a fresh sealed type per
 * step would just re-carry the same accumulated fields forward at each transition
 * ([ManualBookingUiState]'s own doc comment) — a single mutable record with a `step` field is the simpler
 * shape for a linear wizard with no branching re-entry.
 */
@HiltViewModel
internal class ManualBookingViewModel
    @Inject
    constructor(
        private val bookingsApi: BookingsApi,
        private val workersApi: WorkersApi,
        private val workerSlotsApi: WorkerSlotsApi,
        private val personsApi: PersonsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ManualBookingUiState>(ManualBookingUiState.Loading)
        val state: StateFlow<ManualBookingUiState> = mutableState.asStateFlow()

        /** The identical "a stray call before the sheet ever opened is a safe no-op, re-opening is not a
         * re-fetch" guard [RescheduleBookingViewModel.open]'s own doc comment states — this sheet has only
         * one thing to be "open" for, unlike that one's per-booking key, so a bare flag is enough. */
        private var opened = false

        /** The last phone [searchPhone] actually queried, digits-only — guards [onPhoneChanged]'s own
         * auto-search from firing again on every keystroke once the number already looks complete. */
        private var lastAutoSearchedDigits: String? = null

        /** `26-283`: non-`null` exactly when this wizard was opened from a client's own detail hub rather
         * than the plain «Добавить вручную» header action — [load] reads it once, after the prefetch
         * lands, to skip [ManualBookingStep.Phone] and [ManualBookingStep.Client] outright and land on
         * [ManualBookingStep.Service] with [ManualBookingClient.Existing] already chosen (`docs/backlog/
         * 26-283-*.md`'s own "skipping phone entry and recognition"). A field rather than a [load]
         * parameter because [open] is what the sheet's own `LaunchedEffect(Unit)` calls, and that call site
         * is the one place that knows which of the two entry points this is. */
        private var prefillClient: PhoneCandidate? = null

        /** [prefillClient] `null` is the ordinary «Добавить вручную» entry point, unchanged; non-`null` is
         * `26-283`'s own pre-bound entry from [ClientDetailSheet]'s «+ Записать». */
        fun open(prefillClient: PhoneCandidate? = null) {
            if (opened) return
            opened = true
            this.prefillClient = prefillClient
            load()
        }

        /** The retry a [ManualBookingUiState.Failed] screen offers. */
        fun refresh() = load()

        /**
         * The one prefetch this flow opens on: the tenant's active service dictionary and its worker
         * roster (which, unlike [BookingsApi.fetchServices], also carries [Worker.calendarId] —
         * [submit]'s own request needs a `calendarId`, and there is no calendar-picker step in this flow
         * to ask for one directly, `docs/backlog/26-268-*.md` §3.6's own "no new operator 'open slots'
         * read" precedent). Fetched together, in parallel — neither depends on the other's answer.
         */
        private fun load() {
            mutableState.update { ManualBookingUiState.Loading }
            viewModelScope.launch {
                val (servicesResult, workersResult) =
                    withContext(ioDispatcher) {
                        val services = async { bookingsApi.fetchServices() }
                        val workers = async { workersApi.fetchWorkers() }
                        services.await() to workers.await()
                    }
                when {
                    servicesResult is ServicesResult.NotConfigured || workersResult is WorkersResult.NotConfigured ->
                        mutableState.update { ManualBookingUiState.NotConfigured }

                    servicesResult is ServicesResult.Failed -> mutableState.update { ManualBookingUiState.Failed(servicesResult.reason) }

                    workersResult is WorkersResult.Failed -> mutableState.update { ManualBookingUiState.Failed(workersResult.reason) }

                    servicesResult is ServicesResult.Loaded && workersResult is WorkersResult.Loaded ->
                        mutableState.update {
                            val wizard =
                                ManualBookingUiState.Wizard(
                                    step = ManualBookingStep.Phone,
                                    // `26-96`'s own archived-services-stay-on-the-wire contract is for a
                                    // *past* booking's own name resolution - a *new* manual booking must
                                    // only ever offer a service still in rotation, the identical filter the
                                    // visitor widget's own booking flow already applies.
                                    services = servicesResult.services.filter { it.isActive },
                                    workers = workersResult.workers,
                                )
                            // `26-283`: the one place [prefillClient] is applied - jump straight past
                            // Phone and Client to Service, with the client already chosen. A `null`
                            // `prefillClient` (the ordinary entry point) leaves `wizard` untouched.
                            val prefill = prefillClient
                            if (prefill != null) {
                                wizard.copy(
                                    step = ManualBookingStep.Service,
                                    phone = prefill.phone,
                                    client = ManualBookingClient.Existing(prefill),
                                )
                            } else {
                                wizard
                            }
                        }
                }
            }
        }

        /** Every keystroke in the phone field, while [ManualBookingStep.Phone] is open. Clears any
         * previous lookup result (a changed number invalidates it) and auto-fires [searchPhone] the first
         * time the digits reach a plausible full length — [lastAutoSearchedDigits] is what stops that from
         * re-firing on every further keystroke once it already has (`docs/backlog/26-268-*.md` §3.4's own
         * "search fires automatically once the number is fully entered"). The explicit «Найти клиента»
         * button calls [searchPhone] directly and needs no such guard - a deliberate tap always searches. */
        fun onPhoneChanged(text: String) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Phone) return@update current
                wizard.copy(phone = text, phoneLookup = PhoneLookupState.Idle)
            }
            val digits = text.digitsOnly()
            if (digits.length >= PHONE_AUTO_SEARCH_DIGITS && digits != lastAutoSearchedDigits) {
                searchPhone()
            }
        }

        /**
         * `26-268`: `GET /api/v1/console/contacts/by-phone` — a no-op while the field is blank or a search
         * is already in flight. [ago.chat.android.core.domain.bookings.BookingsApi.fetchPhoneCandidates]
         * carries no name, so a [PhoneCandidatesResult.Loaded] answer is merged with
         * [ago.chat.android.core.domain.persons.PersonsApi] before it is classified into
         * [PhoneLookupState.None]/[PhoneLookupState.One]/[PhoneLookupState.Many] — the identical
         * display-merge [ContactsViewModel.mergePersonDetails] already performs for the same reason
         * (`adr/0184`).
         */
        fun searchPhone() {
            val wizard = mutableState.value as? ManualBookingUiState.Wizard ?: return
            if (wizard.step != ManualBookingStep.Phone) return
            if (wizard.phoneLookup == PhoneLookupState.Searching) return
            val phone = wizard.phone.trim()
            if (phone.isBlank()) return

            lastAutoSearchedDigits = phone.digitsOnly()
            mutableState.update { (it as? ManualBookingUiState.Wizard)?.copy(phoneLookup = PhoneLookupState.Searching) ?: it }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { bookingsApi.fetchPhoneCandidates(phone) }) {
                    is PhoneCandidatesResult.Loaded -> {
                        val merged = mergeCandidateNames(result.candidates)
                        applyPhoneLookup(classifyCandidates(merged))
                    }

                    PhoneCandidatesResult.NotConfigured -> mutableState.update { ManualBookingUiState.NotConfigured }

                    is PhoneCandidatesResult.Failed -> applyPhoneLookup(PhoneLookupState.Failed(result.reason))
                }
            }
        }

        private fun applyPhoneLookup(lookup: PhoneLookupState) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                // A stray late answer after the operator already moved past the Phone step (chose a
                // candidate, or a fresh edit already reset `phoneLookup` to `Idle`) is simply dropped -
                // the identical "the caller re-reads, does not trust a stale echo" discipline this app's
                // other writes already follow, applied here to a read instead.
                if (wizard.step != ManualBookingStep.Phone) return@update current
                wizard.copy(phoneLookup = lookup)
            }
        }

        /**
         * `26-268`: reads chat's own person registry for every distinct id [candidates] carries and
         * copies a real [PhoneCandidate.displayName] onto the rows that got one back — the identical merge
         * [ContactsViewModel.mergePersonDetails] performs, restated here because [PhoneCandidate] is its
         * own type. A lookup miss or [personsApi] itself failing simply leaves every row exactly as
         * [bookingsApi] already gave it, never a failed recognition step.
         */
        private suspend fun mergeCandidateNames(candidates: List<PhoneCandidate>): List<PhoneCandidate> {
            val personIds = candidates.map { it.personId }.distinct()
            if (personIds.isEmpty()) return candidates

            val persons =
                when (val result = withContext(ioDispatcher) { personsApi.fetchPersons(personIds) }) {
                    is PersonsResult.Loaded -> result.persons
                    is PersonsResult.Failed -> return candidates
                }

            val personsById = persons.associateBy { it.personId }
            if (personsById.isEmpty()) return candidates

            return candidates.map { candidate ->
                val person = personsById[candidate.personId] ?: return@map candidate
                candidate.copy(displayName = person.displayName ?: candidate.displayName)
            }
        }

        /**
         * «Это он» (one match), or a tap on a pick-list row (several matches) — the operator's own identity
         * assertion (`adr/0147`: recognition surfaces, the human confirms), reusing [candidate]'s own
         * person rather than minting a new one. Advances to [ManualBookingStep.Client], which then renders
         * read-only (`docs/backlog/26-268-*.md` §3.4's own "skip name/email re-entry").
         */
        fun chooseCandidate(candidate: PhoneCandidate) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Phone) return@update current
                wizard.copy(step = ManualBookingStep.Client, client = ManualBookingClient.Existing(candidate))
            }
        }

        /**
         * «Новый клиент» (from any of the four Phone-step frames) or «Продолжить как нового» (no match at
         * all) — every one of those calls this same method, since v1 always mints a genuinely new client
         * once the operator declines every surfaced candidate (`docs/backlog/26-268-*.md` §3.4).
         */
        fun chooseNewClient() {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Phone) return@update current
                wizard.copy(
                    step = ManualBookingStep.Client,
                    client = ManualBookingClient.New("", ""),
                    newClientName = "",
                    newClientEmail = "",
                )
            }
        }

        /** «Изменить» beside the phone field — back to a blank lookup on the same step, so the operator can
         * retype the number without losing their place in the flow. */
        fun editPhone() {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                wizard.copy(step = ManualBookingStep.Phone, phoneLookup = PhoneLookupState.Idle, client = null)
            }
        }

        fun onNewClientNameChanged(name: String) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                val client = wizard.client as? ManualBookingClient.New ?: return@update current
                wizard.copy(newClientName = name, client = client.copy(name = name))
            }
        }

        fun onNewClientEmailChanged(email: String) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                val client = wizard.client as? ManualBookingClient.New ?: return@update current
                wizard.copy(newClientEmail = email, client = client.copy(email = email))
            }
        }

        /** «Далее» on [ManualBookingStep.Client] — a no-op for a new client with a still-blank name (the
         * only client-side validation this flow makes; every other rule is the server's, per the same
         * "one refusal this app makes on its own" discipline [BookingActionErrorUi.InvalidDuration]'s own
         * doc comment states). An [ManualBookingClient.Existing] client has nothing to validate. */
        fun confirmClientStep() {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Client) return@update current
                val client = wizard.client ?: return@update current
                if (client is ManualBookingClient.New && client.name.isBlank()) return@update current
                wizard.copy(step = ManualBookingStep.Service)
            }
        }

        /** A tap on a service row selects and advances in one motion, rather than a select-then-"Далее"
         * pair — `docs/backlog/26-268-*.md`'s own "keep it simple", and there is nothing else a service row
         * needs before moving on (unlike the Client step's own two text fields). */
        fun selectService(service: ConfiguredService) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Service) return@update current
                wizard.copy(step = ManualBookingStep.Worker, selectedService = service)
            }
        }

        /** A tap on a master row - the identical one-motion select-and-advance [selectService] already
         * uses, then triggers the same-worker slot read [RescheduleBookingViewModel.load] already
         * establishes (filtered to [WorkerSlotStatus.Available], the identical reason that class's own doc
         * comment states: every other status is not a legal target to book into). Advances straight to
         * [ManualBookingStep.Date] — the read this kicks off covers every day in the default range at once
         * ([ManualBookingStep]'s own doc comment), so [ManualBookingStep.Date] and [ManualBookingStep.Slot]
         * both read from it rather than either one fetching its own slice. */
        fun selectWorker(worker: Worker) {
            val current = mutableState.value as? ManualBookingUiState.Wizard ?: return
            if (current.step != ManualBookingStep.Worker) return

            mutableState.update {
                (it as? ManualBookingUiState.Wizard)?.copy(
                    step = ManualBookingStep.Date,
                    selectedWorker = worker,
                    loadingSlots = true,
                    slots = emptyList(),
                    selectedDate = null,
                    selectedSlot = null,
                    actionError = null,
                ) ?: it
            }

            viewModelScope.launch {
                val today = LocalDate.now(ZoneOffset.UTC)
                val range = defaultWorkerSlotsRange(today)
                when (val result = withContext(ioDispatcher) { workerSlotsApi.fetchSlots(worker.workerId, range.from, range.to) }) {
                    is WorkerSlotsResult.Loaded ->
                        mutableState.update { state ->
                            (state as? ManualBookingUiState.Wizard)?.copy(
                                loadingSlots = false,
                                slots = result.slots.filter { it.status == WorkerSlotStatus.Available },
                            ) ?: state
                        }

                    WorkerSlotsResult.NotConfigured -> mutableState.update { ManualBookingUiState.NotConfigured }

                    is WorkerSlotsResult.Refused ->
                        mutableState.update { state ->
                            (state as? ManualBookingUiState.Wizard)?.copy(
                                loadingSlots = false,
                                actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                            ) ?: state
                        }

                    is WorkerSlotsResult.Failed ->
                        mutableState.update { state ->
                            (state as? ManualBookingUiState.Wizard)?.copy(
                                loadingSlots = false,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            ) ?: state
                        }
                }
            }
        }

        /** A tap on a day row - selects and advances to [ManualBookingStep.Slot], the identical one-motion
         * shape [selectService]/[selectWorker] already use. No fetch here: [localDate] is one of
         * [ManualBookingUiState.Wizard.slots]'s own [WorkerSlot.localDate] values, already on hand from
         * [selectWorker]'s own read ([ManualBookingStep]'s own doc comment). */
        fun selectDate(localDate: String) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Date) return@update current
                wizard.copy(step = ManualBookingStep.Slot, selectedDate = localDate)
            }
        }

        /** A tap on an available slot - selects and advances straight to Проверьте, the identical
         * one-motion shape [selectService]/[selectWorker] already use; the run's real length is computed
         * server-side at [submit] time, never re-derived here (`docs/backlog/26-268-*.md` §3.4's own
         * "the length is decided server-side, not preread"). */
        fun selectSlot(slot: WorkerSlot) {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                if (wizard.step != ManualBookingStep.Slot) return@update current
                wizard.copy(step = ManualBookingStep.Review, selectedSlot = slot)
            }
        }

        /**
         * «Назад» — one step back from wherever the operator is now, every already-entered field left
         * exactly as it was (author feedback 2026-09-29: the wizard used to have no way back at all, so a
         * changed mind on an earlier step meant dismissing the whole sheet and starting over). A plain
         * `copy(step = ...)` is enough because every field this flow accumulates already lives on the one
         * [ManualBookingUiState.Wizard] record ([ManualBookingUiState]'s own doc comment) — nothing to
         * reconstruct, nothing to re-fetch; the phone lookup, the client, the chosen service/master/day all
         * simply stay put and render again exactly as the operator left them. A no-op on
         * [ManualBookingStep.Phone] (nothing before it to go back to) and on any non-[ManualBookingUiState.Wizard]
         * state.
         */
        fun back() {
            mutableState.update { current ->
                val wizard = current as? ManualBookingUiState.Wizard ?: return@update current
                val previous = ManualBookingStep.entries.getOrNull(wizard.step.ordinal - 1) ?: return@update current
                wizard.copy(step = previous)
            }
        }

        /**
         * «Создать запись» — `POST /api/v1/console/bookings/manual`. A no-op if a submit is already in
         * flight, or if any earlier step's own answer is somehow missing (unreachable in practice: Review
         * is only reached once every earlier step has one, the identical defensive completeness
         * [WorkerRecutDrillDownPage]'s own fallback states elsewhere in this package).
         *
         * [ManualBookingClient.Existing.candidate.personId] becomes [reusePersonId]; a
         * [ManualBookingClient.New] sends `null` there and the typed name/email instead — the exact split
         * [ago.chat.android.core.domain.bookings.BookingsApi.createManualBooking]'s own doc comment
         * states. [Worker.calendarId] being `null` here is the one truly defensive branch: every worker
         * this flow can reach was filtered from a roster read that resolves it, so a `null` would mean a
         * worker with no calendar at all reached this screen - refused client-side with the same honest
         * "unexpected" classification a genuine transport failure gets, never a crash.
         */
        fun submit() {
            val wizard = mutableState.value as? ManualBookingUiState.Wizard ?: return
            if (wizard.step != ManualBookingStep.Review || wizard.submitting) return
            val service = wizard.selectedService ?: return
            val worker = wizard.selectedWorker ?: return
            val slot = wizard.selectedSlot ?: return
            val client = wizard.client ?: return

            val calendarId = worker.calendarId
            if (calendarId == null) {
                mutableState.update {
                    (it as? ManualBookingUiState.Wizard)?.copy(
                        actionError = BookingActionErrorUi.Unavailable(BookingsQueueFailure.Unexpected),
                    ) ?: it
                }
                return
            }

            val name: String
            val reusePersonId: String?
            val email: String?
            when (client) {
                is ManualBookingClient.New -> {
                    name = client.name
                    reusePersonId = null
                    email = client.email.trim().takeIf { it.isNotBlank() }
                }

                is ManualBookingClient.Existing -> {
                    name = client.candidate.displayName ?: ""
                    reusePersonId = client.candidate.personId
                    email = null
                }
            }

            mutableState.update { (it as? ManualBookingUiState.Wizard)?.copy(submitting = true, actionError = null) ?: it }

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        bookingsApi.createManualBooking(
                            calendarId = calendarId,
                            serviceId = service.serviceId,
                            workerId = worker.workerId,
                            startEventId = slot.eventId,
                            name = name,
                            phone = wizard.phone.trim(),
                            reusePersonId = reusePersonId,
                            email = email,
                        )
                    }
                when (result) {
                    is ManualBookingResult.Created ->
                        mutableState.update {
                            ManualBookingUiState.Created(
                                bookingId = result.bookingId,
                                startsAt = result.startsAt,
                                endsAt = result.endsAt,
                            )
                        }

                    is ManualBookingResult.Refused ->
                        mutableState.update { state ->
                            (state as? ManualBookingUiState.Wizard)?.copy(
                                submitting = false,
                                actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                            ) ?: state
                        }

                    is ManualBookingResult.Failed ->
                        mutableState.update { state ->
                            (state as? ManualBookingUiState.Wizard)?.copy(
                                submitting = false,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            ) ?: state
                        }
                }
            }
        }
    }

private fun classifyCandidates(candidates: List<PhoneCandidate>): PhoneLookupState =
    when (candidates.size) {
        0 -> PhoneLookupState.None
        1 -> PhoneLookupState.One(candidates[0])
        else -> PhoneLookupState.Many(candidates)
    }

private fun String.digitsOnly(): String = filter { it.isDigit() }

/** A Russian mobile number, country code included, is 11 digits - the threshold
 * [ManualBookingViewModel.onPhoneChanged] treats as "looks complete enough to search"
 * (`docs/backlog/26-268-*.md` §3.4's own "search fires automatically once the number is fully entered"). */
private const val PHONE_AUTO_SEARCH_DIGITS = 11
