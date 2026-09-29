package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBooking
import java.time.OffsetDateTime

/**
 * `26-269`: [ClientDetailViewModel]'s whole state — the identical three/four-arm shape every sibling
 * screen on this port already establishes ([ContactsUiState]/[ConfirmedBookingsUiState]), restated here
 * because [Loaded] carries this hub's own genuinely different shape: a [Contact] the caller already had
 * (Клиенты's own row, never re-fetched — there is no "one contact" read on this port, only the list),
 * plus the two *new* per-client reads this item adds ([upcoming]/[past] bookings, [dialogConversationId]).
 */
internal sealed interface ClientDetailUiState {
    data object Loading : ClientDetailUiState

    /**
     * [contact] is this screen's own copy of the row the operator tapped — reveal/confirm-phone mutate
     * it here, never propagating back to [ContactsViewModel]'s own list, the identical "each screen holds
     * its own copy of a display-merged row" precedent [ConfirmedBookingsUiState.Loaded]'s reveal already
     * establishes for its own, separately-held [ago.chat.android.core.domain.bookings.ConfirmedBooking]
     * rows (a reveal in Утверждены does not update Клиенты either).
     *
     * [upcoming]/[past] are the client-side split of one [PersonBookingsResult.Loaded] answer — see
     * [splitPersonBookings] for the rule. [selectedSegment] is which of the two the body currently shows;
     * held here rather than as a bare `remember` in the composable, the identical "state about *this
     * loaded screen*, not a transient UI toggle" reasoning [ContactsUiState.Loaded.searchQuery]'s own doc
     * comment states for its own field.
     *
     * [dialogConversationId] is `conversations.firstOrNull()?.conversationId` from
     * [ago.chat.android.core.domain.persons.PersonsApi.fetchPersonConversations] — already
     * active-else-most-recent-ordered server-side (that port's own doc comment) — or `null` when the
     * client has no conversation at all (a `26-268` manual client, for one) or when that read failed;
     * either way «Открыть диалог» is hidden, never greyed (`docs/backlog/26-269-*.md` §4's own "hide,
     * don't grey" rule, restated from the confirmed-bookings screen's identical treatment of a null
     * `originConversationId`). This is a deliberate degrade, not folded into [ClientDetailUiState.Failed]:
     * the whole hub must not fail just because the dialog side-read did
     * (`adr/0184` decision 4: "degrades to no dialog to open yet").
     */
    data class Loaded(
        val contact: Contact,
        val upcoming: List<PersonBooking>,
        val past: List<PersonBooking>,
        val dialogConversationId: String?,
        val selectedSegment: ClientDetailSegment = ClientDetailSegment.Upcoming,
        val revealing: Boolean = false,
        val confirmingPhone: Boolean = false,
        val actionError: BookingActionErrorUi? = null,
        /** `26-275`: which upcoming booking's own «Отменить» is on the network right now — a *set*, the
         * identical [ContactsUiState.Loaded.revealingCustomerIds] reasoning restated for a per-row veto
         * write instead of a per-customer reveal: a second row's own cancel must stay tappable while a
         * first is still in flight. Defaulted so every existing call site keeps compiling unchanged. */
        val cancellingBookingIds: Set<String> = emptySet(),
    ) : ClientDetailUiState {
        val hasDialog: Boolean get() = dialogConversationId != null

        val visibleBookings: List<PersonBooking>
            get() = if (selectedSegment == ClientDetailSegment.Upcoming) upcoming else past

        /** `26-269` polish (B5): the header pill's own count - every booking this hub has ever loaded
         * for this client, past and upcoming alike, no new read: both lists are already in hand from the
         * one [PersonBooking] fetch [ClientDetailViewModel.open] already made. */
        val totalBookingsCount: Int
            get() = upcoming.size + past.size

        /** `26-269` polish (B5): the «Постоянный клиент» pill's own threshold - the identical
         * `bookingCount > 1` rule [ago.chat.android.bookings.ManualBookingScreen.kt]'s own
         * `phoneCandidateHistoryLabel` already draws for a phone-recognition candidate ("Постоянный · N
         * записей" once a second booking exists), restated here for the hub's own header rather than a
         * freshly invented cutoff: one booking is a first visit, not yet a pattern - two or more is what
         * this app's own "returning" wording is already spent on elsewhere. */
        val isReturningClient: Boolean
            get() = totalBookingsCount > 1

        /** `26-269` polish (B6): «Первый визит» - the earliest [PersonBooking.localDate] among [past]
         * bookings only, never [upcoming] (a booking that has not happened yet is not a visit yet) and
         * never `ago-calendar`'s own `ContactResponse.FirstSeenAt`/`PersonRecord.FirstSeenAt` - verified
         * against that field's own write path
         * (`Ago.Calendar.Infrastructure.Postgres.BookingStore.UpsertPersonRecordSql`): it is stamped the
         * moment this person's `person_records` row is first upserted, at *booking-attempt* time, not at
         * an actual appointment's own date - a client who booked once, months ago, for a visit still
         * weeks out would show a "first visit" that has not happened yet. [past] is already exactly the
         * set of appointments that *did* happen ([splitPersonBookings]'s own doc comment) and is already
         * loaded for this same hub - the simplest source that is also honestly correct, not a fourth
         * [Contact] field carrying a different fact than its own name promises. `null` when [past] is
         * empty - no visit has happened yet, never a fabricated date. */
        val firstVisitLocalDate: String?
            get() = past.minByOrNull { it.startsAt }?.localDate

        /** `26-269` polish (B6): «Последний визит» - the identical reasoning [firstVisitLocalDate] states,
         * the latest date instead of the earliest. */
        val lastVisitLocalDate: String?
            get() = past.maxByOrNull { it.startsAt }?.localDate
    }

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.bookings.PendingBookingsResult.NotConfigured]'s own doc comment
     * explains — unreachable in practice (a client-detail hub only ever opens from a Клиенты row, and
     * that segment is itself gated on the calendar being configured), kept for the same "the type must be
     * exhausted honestly" reason [KtorBookingsApi]'s own write methods state for their own unreachable
     * `NotConfigured`-shaped case. */
    data object NotConfigured : ClientDetailUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ClientDetailUiState
}

/**
 * `26-269`: Предстоящие/Прошедшие — a property of a *booking* (`docs/backlog/26-269-*.md` §3.5/§9: "past/
 * future is a property of a booking, not a client"), so this enum lives beside [ClientDetailUiState]
 * rather than being a client-wide flag. A plain UI-layer enum, the identical "nothing outside this
 * screen's own composables and view-model wiring needs to know these names exist" reasoning
 * [BookingsTab]/[DateStripEdgeLoad] already state for themselves.
 */
internal enum class ClientDetailSegment {
    Upcoming,
    Past,
}

/**
 * `26-279` (B8): which of the hub's two navigation-state slots a booking row's own tap should write to,
 * and whether the booking-detail card it then opens is [readOnly] — pulled out of [ClientBookingRow]'s own
 * `onClick` lambda into a plain function so a JVM `test` can assert the routing directly, the identical
 * "assert the wiring without a composition host" reason [splitPersonBookings] below is already a plain
 * function. Past is [readOnly] — nothing left to move or cancel about a visit that already happened
 * (`26-269` B9's own rule, unchanged); upcoming is not — `26-279`'s own «Перенести»/«Отменить» card, the
 * behaviour this item adds in place of the row jumping straight into [RescheduleBookingSheet].
 */
internal enum class ClientDetailCardTarget(
    val readOnly: Boolean,
) {
    Upcoming(readOnly = false),
    Past(readOnly = true),
}

/** [ClientDetailCardTarget]'s own resolution from the segment a tapped row belongs to. */
internal fun clientDetailCardTarget(segment: ClientDetailSegment): ClientDetailCardTarget =
    if (segment == ClientDetailSegment.Upcoming) ClientDetailCardTarget.Upcoming else ClientDetailCardTarget.Past

/**
 * `26-269`: the hub's own Предстоящие/Прошедшие split — a booking's [PersonBooking.startsAt] before
 * [now] is Прошедшие, otherwise Предстоящие. [now] is a parameter, not read from inside this function, so
 * a unit test can assert the boundary with a fixed instant rather than depending on the wall clock — the
 * identical "no hidden clock" posture [ago.chat.android.bookings.ConfirmedBookingsViewModel]'s own
 * `anchorDate` field takes for its own date math (an explicit value threaded in and out, never a bare
 * `OffsetDateTime.now()` buried inside the function body).
 *
 * A booking whose [PersonBooking.startsAt] fails to parse sorts into [past] — the same "never let a
 * malformed value crash the split" posture, resolved toward the branch a stale-but-unparseable record is
 * more likely to belong to (a booking made under a scheme this app no longer expects is far more likely
 * old than upcoming).
 *
 * [PersonBooking.status] plays no part in the split itself — a client-side rule and rule alone
 * (`docs/backlog/26-269-*.md` §3.5's own "the split lives where the bookings are"); the design mockup's
 * own examples ([PersonBookingStatus.NoShow] rows only ever appearing under Прошедшие) fall out of the
 * time split alone, since a booking cannot be marked a no-show before its own start time passes.
 *
 * Sort order: [upcoming] ascending by [PersonBooking.startsAt] (the soonest one first — what a phone call
 * about "when am I booked" wants to see); [past] descending (the most recent visit first — a history
 * read top-to-bottom).
 */
internal fun splitPersonBookings(
    bookings: List<PersonBooking>,
    now: OffsetDateTime,
): Pair<List<PersonBooking>, List<PersonBooking>> {
    val (upcoming, past) =
        bookings.partition { booking ->
            val startsAt = runCatching { OffsetDateTime.parse(booking.startsAt) }.getOrNull()
            startsAt != null && startsAt.isAfter(now)
        }
    return upcoming.sortedBy { it.startsAt } to past.sortedByDescending { it.startsAt }
}

/**
 * `26-269` polish (B9), generalised `26-279` (B8): a booking, reduced to the identical shape
 * [ConfirmedBookingDetailSheet] already knows how to draw, so the client hub's own booking-detail card -
 * past (`readOnly = true`) and, since `26-279`, upcoming (`readOnly = false`) alike - is that same sheet
 * rather than a second, drifting copy of the Услуга/Мастер/Телефон/Источник rows. Named for the shape it
 * produces, not the segment it started on: `26-269` only ever called this for a past row (hence the
 * original `asReadOnlyConfirmedBooking` name), and `26-279`'s own upcoming card reuses it verbatim -
 * `readOnly` on the *sheet*, not a fork of this mapping, is what tells the two segments apart.
 *
 * Every field [ConfirmedBooking] needs is either already on [this] ([PersonBooking.serviceName]/
 * [PersonBooking.workerDisplayName]/[PersonBooking.originConversationId] verified present - the gap
 * analysis's own open question, settled by reading [PersonBooking]'s own declaration) or comes from
 * [contact] instead of [PersonBooking.phone]/[PersonBooking.masked]: the phone shown here is deliberately
 * the *contact's* current, reveal-reactive value - the same single «Показать» the hub's header already
 * offers for this same customer - not the booking row's own snapshot, which [ClientDetailViewModel.reveal]
 * never touches and would silently stay masked after a reveal the operator just performed one screen up.
 */
internal fun PersonBooking.asConfirmedBooking(contact: Contact): ConfirmedBooking =
    ConfirmedBooking(
        bookingId = bookingId,
        calendarId = calendarId,
        workerId = workerId,
        workerDisplayName = workerDisplayName,
        serviceId = serviceId,
        serviceName = serviceName,
        customerId = contact.customerId,
        customerDisplayName = contact.displayName,
        startsAt = startsAt,
        endsAt = endsAt,
        localDate = localDate,
        weekday = weekday,
        phone = contact.phone,
        masked = contact.masked,
        originConversationId = originConversationId,
    )
