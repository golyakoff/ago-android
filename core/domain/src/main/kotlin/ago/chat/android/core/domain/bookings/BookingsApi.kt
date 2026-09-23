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
 * `26-48`'s own reading of `26-59` (not landed at the time this port was written, per this item's own
 * brief): a network failure never reaches the operator as a raw exception class name or a hostname, and
 * the way to keep a fifth `describe()` copy from being born here is to never hold a message string in
 * this port at all. [Transport]/[Unexpected] is the classification; the Russian sentence for each is
 * `:app`'s own job (a string resource, not a field on this type) — the identical "adapter classifies,
 * UI renders" split `26-59`'s own Scope states for the shared version of this idea it has not landed
 * yet.
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
