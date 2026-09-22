package ago.chat.android.data.conversations

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Which half of the queue a cached row belongs to — Room's own record of the same split
 * `ConversationQueue`'s two lists carry, since a flat table has no second list to put a row in.
 *
 * A plain `String` column rather than a Kotlin enum: Room has no built-in column mapping for an enum
 * type (it needs a hand-registered `TypeConverter` either way), so a raw constant pair here is the
 * same amount of code with one fewer moving part — [RoomConversationListCache] is the one place that
 * ever compares against these two values, exactly as it is the one place that translates every other
 * field between this entity and `:core:domain`'s `ConversationSummary`.
 */
internal object ConversationBucket {
    const val WAITING: String = "waiting"
    const val ASSIGNED_TO_ME: String = "assigned_to_me"
}

/**
 * `26-14`: one cached row. A conversation can appear in both buckets across two different writes (moved
 * from `Waiting` to `AssignedToMe` by a claim) but never in both at once — the composite primary key
 * exists so [ConversationRowDao.replaceAll] can be a plain "delete everything, insert everything" without
 * a stale row from the *other* bucket surviving a write that moved a conversation between them.
 *
 * `visitorId`/`emojiCreature`/`emojiFood`/`visitorName`/`createdAt`/`operatorUnreadCount` mirror
 * `ConversationSummary` (`:core:domain`) field for field — this entity exists only so Room has an
 * `@Entity` to persist; [RoomConversationListCache] is the one place that translates between the two,
 * the same "no third-party annotation reaches :core:domain" boundary `AgoActiveSite`/`SessionStore`
 * already draw for `EncryptedSharedPreferences`.
 */
@Entity(tableName = "conversation_rows", primaryKeys = ["conversationId", "bucket"])
internal data class ConversationRowEntity(
    val conversationId: String,
    /** One of [ConversationBucket.WAITING] / [ConversationBucket.ASSIGNED_TO_ME]. */
    val bucket: String,
    val visitorId: String,
    val emojiCreature: String?,
    val emojiFood: String?,
    val visitorName: String?,
    val createdAt: String,
    val operatorUnreadCount: Int,
)

/**
 * `26-14`: the one fact [RoomConversationListCache.read] needs that no row can carry on its own — "has
 * this cache ever been written at all" — see [ago.chat.android.core.domain.conversations.ConversationListCache]'s
 * own doc comment for why `null` and "written, and genuinely empty" must not be the same answer. A
 * single row, `id` always `0`, present once [ConversationRowDao.replaceAll] has run at least once and
 * never before.
 */
@Entity(tableName = "conversation_cache_written")
internal data class ConversationCacheWrittenEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
