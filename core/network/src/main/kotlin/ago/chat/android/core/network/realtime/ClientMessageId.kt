package ago.chat.android.core.network.realtime

import java.util.UUID

/**
 * `26-15`: `ago-console`'s `realtime/protocol/dedup.ts` — `newClientMessageId`, ported by name (this
 * backlog item's own Verified note: "the two mechanisms this item ports, by name" — the other,
 * `SeenMessageIds`, was already ported as [SeenHubIds] in `26-13`). A client-generated id attached to
 * every send, for echo suppression and retry-dedup.
 *
 * A random UUID is not decoration here — `OperatorHub.SendMessageAsync`'s own `clientMessageId`
 * parameter rides all the way through to `Conversation.AddMessage` (`ago-chat`, `Ago.Chat.Domain`),
 * which returns the *original* message unchanged for a repeated id rather than minting a second one.
 * So a caller that retries a send after a dropped connection with this exact same id back cannot
 * produce two messages server-side — the real, load-bearing half of `CLAUDE.md` rule 5's "consumers
 * are idempotent" claim for this path, confirmed by reading `Conversation.AddMessage` itself
 * (`ago-chat/src/Ago.Chat.Domain/Conversation.cs`), not assumed from the parameter's name.
 *
 * The Kotlin `UUID` type's canonical string form is identical to what `crypto.randomUUID()` produces
 * on the web and what `System.Text.Json` parses a `Guid?` hub parameter from — there is exactly one
 * wire representation, so the two clients need not agree on anything beyond "a v4 UUID as text".
 */
public fun newClientMessageId(): String = UUID.randomUUID().toString()
