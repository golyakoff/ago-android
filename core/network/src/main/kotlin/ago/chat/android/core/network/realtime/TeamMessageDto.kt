package ago.chat.android.core.network.realtime

/**
 * `26-54`: `Ago.Chat.Contracts.TeamMessageDto`, hand-written the same way [MessageDto] already is
 * (`adr/0178`: "there is nothing to generate from") — a separate shape from [MessageDto], not a reuse,
 * ported field-for-field from `ago-console`'s own `realtime/protocol/types.ts`. A team message has no
 * conversation, no attachment and no author kind (the author is always an operator), so reusing
 * [MessageDto] would either leave those fields always-null noise or invite treating the two message
 * kinds as interchangeable, when a team message is deliberately invisible to a visitor.
 *
 * [authorDisplayName]/[authorEmail] are resolved server-side at read time and are `null` for the one
 * row shape that carries neither. [authorIsAdmin] is the one distinction this screen draws — stamped at
 * send time from whether the author held `site:manage_operators` for the site that moment, never
 * recomputed client-side.
 *
 * [body] is `null` exactly when [removedAt] is not — the server's own tombstone. Removing a team
 * message is `26-54`'s own Out of scope (a separate item, `RemoveTeamMessageButton`'s own confirmation
 * flow), but the *push* that announces one — [OperatorHubEvents.teamMessageRemovals] — still arrives on
 * this connection regardless of who removed it, so a message already on screen must still render the
 * placeholder rather than keep showing content the server has redacted.
 */
public data class TeamMessageDto(
    val id: String,
    val sequence: Long,
    val authorOperatorId: String = "",
    val authorDisplayName: String? = null,
    val authorEmail: String? = null,
    val authorIsAdmin: Boolean = false,
    val body: String? = null,
    val createdAt: String = "",
    val clientMessageId: String? = null,
    val removedAt: String? = null,
)

/**
 * `26-54`: `Ago.Chat.Contracts.TeamHistoryPage` — the team room's own keyset page, the identical shape
 * [HistoryPage] already is over [MessageDto], here over [TeamMessageDto]. Returned by both
 * `GetTeamHistoryAsync` (a real `beforeSequence` cursor pages backward) and `GetTeamDeltaAsync` (every
 * message strictly after a given sequence, the reconnect catch-up path — [nextBeforeSequence] is simply
 * unused by that caller, not a different wire shape).
 */
public data class TeamHistoryPage(
    val messages: List<TeamMessageDto> = emptyList(),
    val nextBeforeSequence: Long? = null,
)
