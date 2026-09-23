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
