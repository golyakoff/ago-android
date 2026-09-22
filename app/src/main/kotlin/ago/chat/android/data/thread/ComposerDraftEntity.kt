package ago.chat.android.data.thread

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `26-15`: one saved composer draft. `conversationId` is the whole primary key — one draft per
 * conversation, the same "keyed by conversation, never a single slot"
 * [ago.chat.android.core.domain.conversations.ComposerDraftStore] doc comment states, since an
 * operator can hold several assigned conversations at once.
 *
 * There is no "written at all" marker the way `ConversationCacheWrittenEntity` needs one — a draft
 * genuinely absent and a draft never saved are the identical fact here (`ComposerDraftStore.read`'s
 * own `null`), unlike the queue cache, where "never written" and "written, and empty" have to be told
 * apart. A row simply not existing for a conversation *is* "no draft", with no separate marker needed.
 */
@Entity(tableName = "composer_drafts")
internal data class ComposerDraftEntity(
    @PrimaryKey val conversationId: String,
    val draft: String,
)
