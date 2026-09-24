package ago.chat.android.core.domain.bookings

/**
 * `26-48`: the port [ago.chat.android.bookings.BookingsViewModel] (`:app`) reads through — declared
 * here and implemented in `:core:network` (`KtorBookingsApi`), the identical split
 * [ago.chat.android.core.domain.conversations.ConversationsApi] already establishes for the
 * conversation queue. The dependency rule is what puts it here: a view model holding an `HttpClient`
 * directly could not be tested without one, and every HTTP-shaped decision (which status means what,
 * which base URL to call) belongs on the far side of this interface, in the adapter.
 *
 * One call, matching this item's own one promise — a read-only pending-booking queue
 * (`docs/backlog/26-48-*.md`'s own Scope: "the whole promise... shows this tenant's real
 * pending-booking queue, read-only"). Every write (reject/cancel) is `26-49`, a separate port on a
 * separate item.
 *
 * **The name is a historical artifact, the same way [BookingsQueueFailure]'s own doc comment says its
 * is.** This is the port for `Ago.Calendar.Api`'s whole `/api/v1/console` surface as this app uses it,
 * not for bookings alone — `26-52` put contacts on it, `26-53` a phone reveal, and `26-96` the service
 * dictionary and its edit. A fourth and fifth adapter would each repeat the same base-URL check, the
 * same `X-Ago-Active-Site` plumbing and the same [BookingsQueueFailure] classification for no
 * separation that exists in the deployment: one origin, one token, one set of failure modes. Renaming
 * it is a mechanical change worth its own item, not a side effect of this one.
 */
public interface BookingsApi {
    /**
     * `GET /api/v1/console/pending-bookings` against `Ago.Calendar.Api`'s own origin — one queue
     * spanning every calendar the tenant has, with no "mine" (`ago-console`'s own
     * `CalendarQueuePage.tsx`, "there is deliberately no filter by calendar").
     */
    public suspend fun fetchPendingQueue(): PendingBookingsResult

    /**
     * `26-51`: `GET /api/v1/console/confirmed-bookings` — every calendar the tenant has, across a
     * business-local date range, both bounds inclusive (`ago-console`'s own `getConfirmedBookings`,
     * `calendarApi.ts:645`). A range, not a single day: the date strip needs the whole range to know
     * which days carry a dot, and only the *rendering* is per-day
     * ([ago.chat.android.bookings.ConfirmedBookingsViewModel]'s own doc comment).
     */
    public suspend fun fetchConfirmedBookings(
        from: String,
        to: String,
    ): ConfirmedBookingsResult

    /**
     * `26-52`: `GET /api/v1/console/contacts` — one row per customer this tenant has ever booked, no
     * date range and no "mine" (`ago-console`'s own `getContacts`, `calendarApi.ts:685`). Gated
     * server-side on `calendar:configure` or `customer:read` — a third, distinct gate from
     * [fetchConfirmedBookings]'s own `customer:read`-alone gate, matching
     * `CalendarContactsPage.tsx:48` exactly. Read-only: every write on this data — a display name, a
     * note, the audited phone reveal, merging two customers — is a separate item
     * (`docs/backlog/26-52-*.md`'s own Out of scope).
     */
    public suspend fun fetchContacts(): ContactsResult

    /**
     * `26-49`: `POST /api/v1/console/bookings/{bookingId}/reject` — a `204`-or-refusal write, the
     * identical shape [ago.chat.android.core.domain.conversations.ConversationsApi.claim] already
     * establishes for the conversation queue: a genuine server refusal (a non-2xx response whose body
     * carried an RFC 7807 `detail`) comes back as [BookingActionResult.Refused], shown verbatim;
     * everything else that kept the write from landing — a dropped connection, a bare non-2xx with no
     * `detail` to show — is [BookingActionResult.Failed]. There is deliberately no confirm counterpart:
     * the queue auto-confirms unless vetoed, so [rejectBooking]/[cancelBooking]/[markNoShow] are this
     * port's whole write surface (`docs/backlog/26-49-*.md`'s own Out of scope: "adding a confirm
     * endpoint... is a product decision, not an Android item").
     */
    public suspend fun rejectBooking(bookingId: String): BookingActionResult

    /** `26-49`: `POST /api/v1/console/bookings/{bookingId}/cancel` — the identical shape [rejectBooking]
     * documents in full. */
    public suspend fun cancelBooking(bookingId: String): BookingActionResult

    /** `26-49`: `POST /api/v1/console/bookings/{bookingId}/no-show` — the identical shape [rejectBooking]
     * documents in full. */
    public suspend fun markNoShow(bookingId: String): BookingActionResult

    /**
     * `26-53`: `POST /api/v1/console/contacts/{customerId}/reveal-phone`, body `{"surface": surface}` —
     * the audited unmask `ago-console`'s own `revealCustomerPhone` (`calendarApi.ts:701`) already
     * establishes, ported onto this port rather than a fourth, phone-specific adapter. [surface] is
     * recorded server-side on the reveal's own audit row — it names *which screen* asked, never which
     * operator (the token already carries that); [BookingRevealSurface] is this app's own closed list of
     * values, one per screen that can reveal, so a caller never invents or borrows the console's own
     * strings (`BookingRevealSurface`'s own doc comment explains why that borrowing would silently
     * corrupt the one record the audit trail exists to keep straight).
     *
     * The identical `204`-or-refusal-or-failure three-way split this port's other writes already use,
     * restated for a call that also has something to return on success: [RevealPhoneResult.Revealed]
     * carries the server's own unmasked number, never one this port unmasks itself
     * (`ago-console`'s own `renderPhone` doc comment: "never unmasked client-side").
     */
    public suspend fun revealCustomerPhone(
        customerId: String,
        surface: String,
    ): RevealPhoneResult

    /**
     * `26-96`: `GET /api/v1/console/configuration`, read for one field — `services`. The console's own
     * `getConfiguration` (`calendarApi.ts`) returns the whole tenant configuration; this app has one
     * screen for one slice of it, so the adapter reads the whole document and hands back only that
     * list rather than modelling calendars, workers, origins and a public key nothing here draws.
     *
     * **Archived services come back too, and that is the point.** The server deliberately does not
     * filter them out of this read — it is what a worker card and a past booking resolve a service
     * name through — so [ConfiguredService.isActive] is a field to render, never a row to drop.
     */
    public suspend fun fetchServices(): ServicesResult

    /**
     * `26-96`: `PUT /api/v1/console/services/{serviceId}` — the edit this product had no endpoint for
     * until that item, and the one write that takes a service out of rotation.
     *
     * **Replace semantics: every field is sent, every time.** The server rewrites the whole record
     * from this body, so a caller flipping only [isActive] still sends the other five as they stand —
     * the identical shape `ago-console`'s own `updateService` uses, and the reason this method takes
     * six parameters rather than a partial patch object.
     *
     * There is deliberately **no `deleteService` beside it**. Four server-side read models resolve a
     * past booking's service *name* through the `services` row, so deleting one would retroactively
     * blank the service on every booking that ever used it — [ConfiguredService.isActive] is what this
     * product offers instead (`Ago.Calendar.Domain.Service.IsActive`'s own remarks).
     *
     * Answers with [BookingActionResult], reused rather than restated: a `204`, a server-authored
     * refusal shown verbatim, or a failure — the identical three-way question the veto writes on this
     * same port already reduce to. A domain refusal an operator can actually act on (a non-positive
     * duration, say) arrives as [BookingActionResult.Refused] carrying the server's own sentence,
     * which is exactly why this write must not invent its own validation messages.
     */
    public suspend fun updateService(
        serviceId: String,
        name: String,
        durationMinutes: Int,
        priceMinorUnits: Int?,
        priceIsFrom: Boolean,
        description: String?,
        isActive: Boolean,
    ): BookingActionResult

    /**
     * `26-74`: `GET /api/v1/console/contacts/phone-reveals` — the tenant's own reveal audit trail, read
     * back rather than performed: `revealCustomerPhone` above is the write this reads the history of.
     * Gated server-side on `calendar:configure`, **not** `customer:read` — deliberately wider than the
     * reveal action itself (`GetPhoneRevealsForTenantHandler`'s own doc comment, `ago-calendar`: revealing
     * one number does not entitle somebody to the whole tenant's own reveal history), so this is the one
     * read on this port whose gate is not implied by any other method already on it.
     *
     * A sixth method on this same port rather than a sixth adapter class — [KtorBookingsApi]'s own class
     * doc comment already states why a fourth/fifth read earned no separate adapter, and this read shares
     * every one of those properties too (`docs/backlog/26-74-*.md`'s own Scope item 1: "no new base URL,
     * no new adapter class if the existing one fits").
     *
     * [before] is the keyset cursor — the last [PhoneReveal.id] from the previous page, or `null` for the
     * first one; [limit] is `null` to take the server's own default
     * (`GetPhoneRevealsForTenantHandler.DefaultLimit`, `ago-calendar`). Both travel as query parameters,
     * the identical shape [fetchConfirmedBookings]'s own `from`/`to` already establish for this port.
     */
    public suspend fun fetchPhoneReveals(
        before: String?,
        limit: Int?,
    ): PhoneRevealsResult
}

/**
 * `26-96`: what asking for the tenant's own service dictionary came back with — the identical
 * three-arm shape [PendingBookingsResult]/[ConfirmedBookingsResult]/[ContactsResult] already establish,
 * restated for the same reason those three are restated from one another: [Loaded] carries
 * [ConfiguredService], a type with no field in common with any of them.
 */
public sealed interface ServicesResult {
    public data class Loaded(
        val services: List<ConfiguredService>,
    ) : ServicesResult

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [PendingBookingsResult.NotConfigured]'s own doc comment explains. */
    public data object NotConfigured : ServicesResult

    /** [BookingsQueueFailure] reused a fourth time - this read reduces to the same "is it me, or is it
     * broken" two-way question, and that type's own doc comment already says its name is a historical
     * artifact of the pending queue having been the first caller. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : ServicesResult
}

/**
 * `26-53`: what asking to unmask one customer's phone came back with — the identical
 * [BookingActionResult] shape restated with one extra arm on success, since this write (unlike
 * reject/cancel/no-show) has a value to hand back rather than a bare acknowledgement.
 */
public sealed interface RevealPhoneResult {
    /** A `200` (or any `2xx`) carrying the real, unmasked number — `CustomerPhoneRevealResponse.Phone`
     * on the wire, read and handed back verbatim, never reformatted or validated by this port. */
    public data class Revealed(
        val phone: String,
    ) : RevealPhoneResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — an operator asking to reveal a number
     * they are not entitled to must see the server's own reason, not a generic network message
     * (`docs/backlog/26-53-*.md`'s own Scope item 5: "a reveal an operator is not entitled to must not
     * look like a network hiccup"). The masked value stays on screen; nothing here ever guesses at an
     * unmasked number from a failure. */
    public data class Refused(
        val detail: String,
    ) : RevealPhoneResult

    /** Everything that is not a genuine server refusal — the identical [BookingActionResult.Failed]
     * classification, reused here for the identical reason. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : RevealPhoneResult
}

/**
 * `26-49`: what vetoing one pending booking (reject/cancel/no-show alike — all three writes share this
 * one result shape) came back with — restated from
 * [ago.chat.android.core.domain.conversations.ClaimResult] rather than reused, because that type lives
 * in a sibling package with its own [ago.chat.android.core.domain.net.NetworkFailure] classification;
 * this port already has its own two-way "is it me, or is it broken" vocabulary in [BookingsQueueFailure],
 * shared by every read on this same interface, and [Failed] reuses it here for the identical reason
 * [ConfirmedBookingsResult.Failed]'s own doc comment gives for reusing it on a second read.
 */
public sealed interface BookingActionResult {
    /** `204 No Content`. The caller already knows what it asked for — a fresh [BookingsApi.fetchPendingQueue]
     * is how the row's own departure from the queue is observed, never assumed from this result alone. */
    public data object Succeeded : BookingActionResult

    /**
     * A non-2xx whose body carried a genuine RFC 7807 `detail` — shown to the operator **as-is**, and
     * never retried automatically. Losing a race with the confirmation sweep (the booking the operator
     * just tried to reject already auto-confirmed, say) surfaces here, worded by the server itself,
     * exactly the way [ago.chat.android.core.domain.conversations.ClaimResult.Refused]'s own doc
     * comment describes for the identical situation on the conversation queue.
     */
    public data class Refused(
        val detail: String,
    ) : BookingActionResult

    /** Everything that is *not* a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show verbatim. [reason] is the same [BookingsQueueFailure] two-way
     * classification every read on this port already uses, never a fabricated `detail` string. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : BookingActionResult
}

/** What answering "what is waiting to auto-confirm" came back with. */
public sealed interface PendingBookingsResult {
    public data class Loaded(
        val bookings: List<PendingBooking>,
    ) : PendingBookingsResult

    /**
     * This deployment has no AGO Calendar backend configured at all
     * ([ago.chat.android.session.OidcConfig.calendarApiBaseUrl] is `null`) — a real, honest state, not
     * a failure: `ago-console`'s own `CalendarQueuePage.tsx` renders the identical case as "not
     * configured" rather than as an error, and this port makes the same distinction for the same
     * reason (`docs/backlog/26-48-*.md`'s own Done-when: "renders a stated 'not configured' message,
     * not a spinner and not a crash").
     */
    public data object NotConfigured : PendingBookingsResult

    /** The call did not answer usefully — see [BookingsQueueFailure] for the two things that can mean. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : PendingBookingsResult
}

/**
 * `26-51`: what answering "what is on for this range" came back with — the identical three-arm shape
 * [PendingBookingsResult] already establishes for the queue, restated rather than shared because the
 * two reads have no field in common to generalise over ([Loaded] carries [ConfirmedBooking], not
 * [PendingBooking]).
 */
public sealed interface ConfirmedBookingsResult {
    public data class Loaded(
        val bookings: List<ConfirmedBooking>,
    ) : ConfirmedBookingsResult

    /** The identical "this deployment does not run AGO Calendar at all" fact [PendingBookingsResult.NotConfigured]'s
     * own doc comment explains. */
    public data object NotConfigured : ConfirmedBookingsResult

    /** [BookingsQueueFailure] is reused rather than a second, identically-shaped enum invented here —
     * despite its name, the type answers only "no network" vs. "something else", a classification this
     * read needs exactly as much as the queue does. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : ConfirmedBookingsResult
}

/**
 * `26-52`: what answering "who are this tenant's customers" came back with — the identical three-arm
 * shape [PendingBookingsResult]/[ConfirmedBookingsResult] already establish, restated rather than
 * shared for the same reason those two are restated from one another: [Loaded] carries [Contact], a
 * type with no field in common with either sibling worth generalising over.
 */
public sealed interface ContactsResult {
    public data class Loaded(
        val contacts: List<Contact>,
    ) : ContactsResult

    /** The identical "this deployment does not run AGO Calendar at all" fact [PendingBookingsResult.NotConfigured]'s
     * own doc comment explains. */
    public data object NotConfigured : ContactsResult

    /** [BookingsQueueFailure] is reused again here, for the identical reason
     * [ConfirmedBookingsResult.Failed]'s own doc comment gives: this read reduces to the same
     * "is it me, or is it broken" two-way question the other two already answer with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : ContactsResult
}

/**
 * `26-74`: what asking for one page of this tenant's own reveal audit trail came back with — the
 * identical three-arm shape [ContactsResult]/[ConfirmedBookingsResult] already establish, restated rather
 * than shared for the same reason those two are restated from one another: [Loaded] carries [PhoneReveal],
 * a type with no field in common with either sibling worth generalising over — and, unique among this
 * port's reads, [Loaded] itself carries paging state ([nextBefore]) neither sibling's own `Loaded` needs.
 */
public sealed interface PhoneRevealsResult {
    public data class Loaded(
        val reveals: List<PhoneReveal>,
        /** `ContactPhoneRevealPageResponse.NextBefore` verbatim — the keyset cursor for the next page,
         * `null` once the oldest row has been reached. */
        val nextBefore: String?,
    ) : PhoneRevealsResult

    /** The identical "this deployment does not run AGO Calendar at all" fact [PendingBookingsResult.NotConfigured]'s
     * own doc comment explains. */
    public data object NotConfigured : PhoneRevealsResult

    /** [BookingsQueueFailure] is reused again here, for the identical reason
     * [ConfirmedBookingsResult.Failed]'s own doc comment gives: this read reduces to the same
     * "is it me, or is it broken" two-way question the other reads on this port already answer with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : PhoneRevealsResult
}

/**
 * `26-48`'s own reading of `26-59` (not landed at the time this port was written, per this item's own
 * brief): a network failure never reaches the operator as a raw exception class name or a hostname, and
 * the way to keep a fifth `describe()` copy from being born here is to never hold a message string in
 * this port at all. [Transport]/[Unexpected] is the classification; the Russian sentence for each is
 * `:app`'s own job (a string resource, not a field on this type) — the identical "adapter classifies,
 * UI renders" split `26-59`'s own Scope states for the shared version of this idea it has not landed
 * yet.
 *
 * `26-51`: also [ConfirmedBookingsResult.Failed]'s own classification — the name is a historical
 * artifact of the pending queue being the first caller, not a queue-specific concept; both reads reduce
 * a failure to the identical two-way question ("is it me, or is it broken") this type already answers.
 */
public enum class BookingsQueueFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status, or a `2xx` whose body was not the
     * shape this adapter promised. Deliberately one bucket rather than a copy of the status code: an
     * operator's actual question ("is it me, or is it broken?") has the same answer either way, and
     * the exact code is not this port's to expose (`ClaimResult.Refused`'s own precedent: only a
     * *server-authored* sentence is ever shown verbatim, and this endpoint has none to offer). */
    Unexpected,
}
