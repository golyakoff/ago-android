package ago.chat.android.core.domain.visitorsummary

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-143`: the port the contact-detail panel's header (`26-147`, H4/H5) reads through for its two
 * per-visitor facts — declared here and implemented in `:core:network` (`KtorVisitorSummaryApi`), the
 * identical `:core:domain` port / `:core:network` adapter split
 * [ago.chat.android.core.domain.contactdetails.ContactDetailsApi] already establishes for the same panel.
 * The dependency rule is what puts it here: a view model holding an `HttpClient` directly could not be
 * tested without one, and every HTTP-shaped decision — which status means what, and turning the wire's
 * ISO-8601 string into an [Instant] — belongs on the far side of this interface, in the adapter. The
 * alternative, injecting a Ktor `HttpClient` into `26-147`'s future view model, would make that view
 * model untestable without a real client and would scatter status-code readings across the UI layer.
 *
 * Confirmed against the real `ago-chat` contract (`Ago.Chat.Contracts.VisitorSummaryResponse`,
 * `ConversationsEndpoints` `GET /api/v1/conversations/{id}/visitor-summary`, shipped by `26-114`), not
 * assumed from the `26-111` design doc's own GAP-1/GAP-2 element map — the same "read it off the server,
 * not the mockup" discipline the sibling `26-115` clients followed. One call only, a pure read.
 */
public interface VisitorSummaryApi {
    /**
     * `GET /api/v1/conversations/{conversationId}/visitor-summary`, `RequireOperatorIdentity`-gated
     * server-side (`ConversationsEndpoints`). Returns the first-seen date and the visitor's own
     * conversation count for the visitor behind the open conversation.
     */
    public suspend fun fetchVisitorSummary(conversationId: String): VisitorSummaryResult
}

/**
 * `Ago.Chat.Contracts.VisitorSummaryResponse`, carrying exactly the two facts the panel header renders —
 * `"Первый визит {date} · N диалог(ов)"` (`docs/design/26-111-thread-contact-detail-panel.md` decisions
 * #4/#5).
 *
 * [firstSeenAt] is an [Instant], not the raw ISO-8601 string [ago.chat.android.core.domain.conversations.ConversationSummary.createdAt]
 * keeps, because — unlike a queue row's `createdAt`, which is only ever rendered *relative to now* as an
 * elapsed label — this is an absolute calendar date shown as `"Первый визит 14 марта"`, which needs no
 * `now` to render; so the string→instant parse is a settled HTTP-shaped concern that belongs in the
 * adapter, and `26-147` formats this [Instant] in the operator's own zone (CLAUDE.md rule 11) with no
 * parsing of its own. It is **nullable** even though the wire field is non-nullable
 * (`DateTimeOffset VisitorFirstSeenAt`): a present-but-unparseable value maps to `null` here rather than
 * failing the whole read, since [conversationCount] is still worth showing when only the date is
 * unreadable — the same "one unreadable field must not blank the whole panel" instinct the sibling
 * clients apply to their own optional fields.
 *
 * [conversationCount] counts this visitor's distinct conversations on this site, **including** the one
 * the operator currently has open — the server already scopes and filters it (`26-114` / §5-Q4); this
 * client never re-filters it (`VisitorSummaryResponse`'s own remarks: `ConversationCount ==
 * historyList.Count + 1`).
 */
public data class VisitorSummary(
    val firstSeenAt: Instant?,
    val conversationCount: Int,
)

/** What reading one conversation's visitor summary came back with — the identical two-arm read-result
 * shape [ago.chat.android.core.domain.contactdetails.ContactDetailsResult] already establishes for the
 * same panel: every cause of "the read failed" renders as the same one banner, so there is no reason to
 * split this type further than the value it carries. */
public sealed interface VisitorSummaryResult {
    public data class Loaded(
        val summary: VisitorSummary,
    ) : VisitorSummaryResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : VisitorSummaryResult
}
