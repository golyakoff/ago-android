package ago.chat.android.data.conversations

import kotlinx.coroutines.flow.Flow

/**
 * `26-46`: "how many messages are waiting unread, across every conversation assigned to me" — the one
 * fact the bottom bar's own Диалоги badge needs, observable from
 * [ago.chat.android.shell.AppShellViewModel] without hoisting
 * [ago.chat.android.conversations.ConversationListViewModel] out of its own nav entry
 * ([ago.chat.android.shell.AppShellScreen]'s own doc comment on why that entry's scoping is not this
 * item's to undo).
 *
 * **Not a `:core:domain` port.** Every other cache/API boundary in this app — [ConversationListCache]
 * itself, `IdentityApi`, `ConversationsApi` — is declared there and implemented here, but that module
 * deliberately carries no `kotlinx-coroutines-core` on its main classpath at all
 * ([ConversationListCache]'s own doc comment: a `Flow`-returning port "would... pull
 * `kotlinx-coroutines-core`'s `Flow` type onto `:core:domain`'s main classpath for a capability nothing
 * uses"). This interface *is* that capability, so it stays where the dependency already lives — `:app`
 * — rather than paying that cost for the one caller that needs it. `public`, not `internal`: it sits in
 * [ago.chat.android.shell.AppShellViewModel]'s own public constructor, and Kotlin forbids a public
 * signature from exposing a less-visible type (the same rule `AppModule`'s own remarks state for
 * [RoomConversationListCache]'s internal counterpart).
 *
 * **Never a second computation of the same number.** [RoomConversationListCache] — already the single
 * process-wide store every row in «Мои»/«Ожидают» is read from and written to — is this interface's
 * only implementation, and [observeTotal] is a `SUM` over the identical `operatorUnreadCount` column
 * [ago.chat.android.conversations.ConversationListViewModel.toRowUi] reads per row. That is what makes
 * the shell's badge and the list's own per-row badges structurally unable to disagree: both read the
 * same rows, and the total is arithmetic the database performs on them, not a parallel tally kept by a
 * second piece of code.
 */
public interface ConversationsUnreadTotal {
    /**
     * `null` until [ConversationListCache] has been written at least once — the identical "never
     * loaded" vs "loaded, and genuinely holds nothing" distinction [ConversationListCache.read]'s own
     * doc comment draws, so a caller can render no badge for either "unknown" or "zero" alike
     * (`docs/backlog/26-39-*.md`'s own rule, restated for this badge). A non-`null` `0` is a real
     * answer — every assigned conversation has been read — not a placeholder.
     *
     * Live: every [ConversationListCache.write] this app ever makes re-emits here, via Room's own
     * `InvalidationTracker` over `conversation_rows` — nothing in this app polls or re-queries by hand
     * for this to update.
     */
    public fun observeTotal(): Flow<Int?>
}
