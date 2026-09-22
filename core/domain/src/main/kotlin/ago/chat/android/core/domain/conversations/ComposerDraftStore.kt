package ago.chat.android.core.domain.conversations

/**
 * `26-15`: where an operator's unsent reply survives leaving the thread screen — including a process
 * death, which a plain `ViewModel` field or `SavedStateHandle` entry cannot survive on its own (a
 * `SavedStateHandle` value only reaches the `Bundle` `onSaveInstanceState` writes, and Android only
 * calls that for a configuration change or a *deliberate* background kill it expects to restore from
 * later — it is not guaranteed against every process death, and this item's own Done-when asks for
 * exactly that guarantee: "including after the process is killed and restored"). The same port/adapter
 * split [ConversationListCache]'s own doc comment draws for Room: the *what* — one draft per
 * conversation, read-then-write, never observed as a stream — lives here in `:core:domain`; the *how*
 * (Room, a `Context`) is `:app`'s own `RoomComposerDraftStore`.
 *
 * Keyed by `conversationId` rather than one single slot: an operator can hold several assigned
 * conversations at once ([ago.chat.android.core.domain.conversations.ConversationQueue]'s own
 * `assignedToMe`), and leaving one half-typed reply to open another must not overwrite it.
 *
 * A blank/empty draft is never written — [ago.chat.android.conversations] `ThreadViewModel` (`:app`)
 * calls [clear] instead, so a conversation with nothing typed leaves no row at all in the store, the
 * same "nothing recorded" shape [ConversationListCache.read]'s own `null` establishes for "never
 * written" rather than "written, and empty".
 */
public interface ComposerDraftStore {
    /** The last draft saved for this conversation, or `null` if there is none — never typed, or
     * cleared after a send. Never throws for "nothing saved yet"; that is this `null`, not an
     * exception a caller has to guard against. */
    public suspend fun read(conversationId: String): String?

    /** Replaces whatever was saved for this conversation. Never called with a blank string — see
     * [clear] for that case. */
    public suspend fun write(
        conversationId: String,
        draft: String,
    )

    /** Removes whatever was saved for this conversation — called once a message actually sends, or
     * when the draft is edited back down to nothing. */
    public suspend fun clear(conversationId: String)
}
