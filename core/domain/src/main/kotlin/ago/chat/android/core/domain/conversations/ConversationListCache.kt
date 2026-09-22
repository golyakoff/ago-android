package ago.chat.android.core.domain.conversations

/**
 * `26-14`: Room's first port. `docs/architecture.md` "Offline": "the app caches for responsiveness,
 * never for correctness" — this interface is exactly that boundary, drawn the same way
 * [ago.chat.android.core.domain.identity.ActiveSiteSelection] draws it for the active site: the *what*
 * (a cache exists, and it is read-then-write) lives in `:core:domain`; the *how* (Room, a `Context`, a
 * `RoomDatabase`) is Android-framework work and lives in `:app` (`RoomConversationListCache`).
 *
 * **Deliberately two plain suspend functions, not a `Flow`.** A `Flow`-returning cache is the more
 * fashionable shape and was considered; it was rejected because [ConversationListViewModel] never
 * actually needs to *observe* the disk — it needs to read it exactly once, at screen start, before the
 * first network answer comes back (`docs/backlog/26-14-*.md`: "the screen never blocks on the network to
 * show what it already has"). Every update after that point comes from a real server answer or a real
 * hub push, never from Room re-emitting its own write back at the screen. A `Flow` here would also pull
 * `kotlinx-coroutines-core`'s `Flow` type onto `:core:domain`'s main classpath for a capability nothing
 * uses — this module's build file's own remarks state why that classpath currently has nothing on it at
 * all, and `suspend` needs no such addition (it is a language feature, not a library).
 *
 * `null` from [read] and an empty [ConversationQueue] are different answers, not the same one: `null`
 * means "this app has never written anything here" (render nothing until the first network answer, the
 * ordinary cold-start case), and an empty queue is a real, previously-confirmed fact about a genuinely
 * idle shop. Conflating them would show the loading state forever on every cold start for an operator
 * whose site has nothing waiting and nothing assigned.
 */
public interface ConversationListCache {
    /** The last queue this app successfully wrote, or `null` if nothing ever has been. Never throws for
     * "nothing cached yet" - that is its `null`, not an exception a caller has to guard against. */
    public suspend fun read(): ConversationQueue?

    /** Replaces whatever was cached with `queue` in full - this screen's whole answer, not a merge of
     * one row, because a partial write here could strand a row Room remembers past the point the server
     * stopped mentioning it (a closed conversation, an unassigned one). */
    public suspend fun write(queue: ConversationQueue)
}
