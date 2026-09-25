package ago.chat.android.core.domain.visitorhistory

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-144`: the port the contact-detail panel's «Прошлые диалоги» section (`26-151`, P1) lists this
 * visitor's other conversations through — declared here and implemented in `:core:network`
 * ([ago.chat.android.core.network.visitorhistory.KtorVisitorHistoryApi]), the identical `:core:domain`
 * port / `:core:network` adapter split the sibling
 * [ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi] already establishes for the same panel.
 * The dependency rule is what puts it here: a view model holding an `HttpClient` directly could not be
 * tested without one, and every HTTP-shaped decision — which status means what, keyset paging, turning
 * the wire's ISO-8601 strings into [Instant]s — belongs on the far side of this interface, in the
 * adapter. The alternative, injecting a Ktor `HttpClient` into `26-151`'s future view model, would make
 * that view model untestable without a real client and scatter status-code readings across the UI layer.
 *
 * **Only the list lives here.** Opening one past conversation *read-only* is a hub call, not a REST one
 * (`GetVisitorHistoryConversationAsync` on [ago.chat.android.core.network.realtime.OperatorHubConnection]),
 * because a transcript is rendered through the same live-thread `HistoryPage`/`MessageDto` machinery the
 * open conversation already uses — the console makes the identical split (a thin REST list, a hub read to
 * open one). So this port carries the list and nothing else.
 *
 * Confirmed against the real `ago-chat` contract (`Ago.Chat.Contracts.VisitorHistoryResponse` /
 * `VisitorHistoryConversationDto`, `GET /api/v1/conversations/{id}/visitor-history`, keyset-paged by
 * `beforeId`/`pageSize`, `26-114`/`adr/0182` having widened it to every visitor on the site), not
 * assumed from the `26-111` design doc's own element map — the same "read it off the server, not the
 * mockup" discipline the sibling clients followed. A pure read; Q6 decided past dialogs are read-only,
 * so there is deliberately no send/act method on this port.
 */
public interface VisitorHistoryApi {
    /**
     * `GET /api/v1/conversations/{conversationId}/visitor-history?beforeId={id}&pageSize={n}`,
     * `RequireOperatorIdentity`-gated server-side plus the same per-conversation "is this operator on
     * it" check every operator-scoped read here uses. Returns one keyset page of the visitor's other
     * conversations on this site (`26-114` widened the scope to every visitor, channel-identified or
     * widget-only alike).
     *
     * **Keyset-paginated, not offset-paginated** — the identical convention
     * [ago.chat.android.core.domain.conversations.ConversationsApi.fetchAllConversations] already uses:
     * [beforeId] is the id of the last row of the previous page and `null` means "the newest page";
     * [VisitorHistoryPage.nextBeforeId] is `null` on the last page (the server sends it only when the
     * page it just cut was full). [pageSize] caps one page's rows; the server defaults it to 20 when a
     * caller omits it, but this port always states it rather than relying on that default.
     */
    public suspend fun fetchVisitorHistory(
        conversationId: String,
        beforeId: String?,
        pageSize: Int,
    ): VisitorHistoryResult
}

/**
 * `26-144`: one keyset page of `GET .../visitor-history`
 * (`Ago.Chat.Contracts.VisitorHistoryResponse`). [nextBeforeId] is `null` on the last page — "null"
 * genuinely means "there is no next page", not "ask again and find out", the identical rule
 * [ago.chat.android.core.domain.conversations.AllConversationsPage.nextBeforeId] already documents.
 */
public data class VisitorHistoryPage(
    val conversations: List<VisitorHistoryConversation>,
    val nextBeforeId: String?,
)

/**
 * `26-144`: one row of the «Прошлые диалоги» list (`Ago.Chat.Contracts.VisitorHistoryConversationDto`)
 * — a thin summary card, never a transcript; the transcript is fetched only when the operator opens the
 * row, through the hub's read-only open-one call.
 *
 * [state] is the server's own `Ago.Chat.Domain.ConversationState` member name verbatim
 * (`"Waiting"`/`"Assigned"`/`"Closed"`), unparsed here — the same raw wire spelling
 * [ago.chat.android.core.domain.conversations.ConversationSummary.state] already travels as, so this app
 * never owns a second vocabulary for the same set; classifying it into a label is `26-151`'s job, in
 * `:app`.
 *
 * The three date fields are [Instant]s (absolute calendar moments the UI formats in the operator's own
 * zone, CLAUDE.md rule 11), parsed in the adapter — the string→instant conversion is exactly the
 * HTTP-shaped decision that interface exists to own, the identical treatment
 * [ago.chat.android.core.domain.visitorsummary.VisitorSummary.firstSeenAt] gets. Each is **nullable**:
 * [closedAt] because a still-open conversation (or one closed before `Conversation.ClosedAt` existed) has
 * none on the wire (`VisitorHistoryConversationDto` remarks), and [startedAt]/[previewCreatedAt] because
 * a present-but-unparseable value maps to `null` rather than failing the whole page — one unreadable
 * timestamp must not blank a whole list of past dialogs, the same instinct the sibling clients apply.
 *
 * [previewBody]/[previewAuthorKind]/[previewCreatedAt] come together or not at all — a conversation with
 * zero messages (created, never written to) has none of the three, rather than a body with no author
 * (`VisitorHistoryConversationDto`'s own remarks); this client mirrors that shape and never fabricates a
 * missing member. [previewAuthorKind] is the same raw `"Visitor"`/`"Operator"`/`"System"` wire spelling
 * [ago.chat.android.core.network.realtime.MessageDto.authorKind] carries, left for `:app` to render.
 */
public data class VisitorHistoryConversation(
    val conversationId: String,
    val state: String,
    val startedAt: Instant?,
    val closedAt: Instant?,
    val previewBody: String?,
    val previewAuthorKind: String?,
    val previewCreatedAt: Instant?,
)

/** What reading one page of the visitor's past-dialog list came back with — the identical two arms, for
 * the identical reasons, [ago.chat.android.core.domain.conversations.AllConversationsResult] already has:
 * every cause of "the read failed" renders as the same one banner, so the type splits no further than the
 * value it carries. */
public sealed interface VisitorHistoryResult {
    public data class Loaded(
        val page: VisitorHistoryPage,
    ) : VisitorHistoryResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : VisitorHistoryResult
}
