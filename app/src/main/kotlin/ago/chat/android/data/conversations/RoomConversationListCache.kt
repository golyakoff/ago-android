package ago.chat.android.data.conversations

import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-14`: [ConversationListCache] over Room — the concrete half of the port that interface's own doc
 * comment describes, the same split `AgoActiveSite`/`SessionStore` already draw for
 * `EncryptedSharedPreferences`. Every translation between `:core:domain`'s wire-shaped
 * [ConversationSummary] and this module's `@Entity` lives here and nowhere else, so a Room annotation
 * never has to appear on a `:core:domain` type.
 */
@Singleton
internal class RoomConversationListCache
    @Inject
    constructor(
        private val dao: ConversationRowDao,
    ) : ConversationListCache {
        override suspend fun read(): ConversationQueue? {
            if (dao.hasEverBeenWritten() == 0) {
                // Never written at all - `ConversationListCache.read`'s own doc comment: this is the
                // ordinary cold start, not a queue that happens to have nothing in it.
                return null
            }

            return ConversationQueue(
                waiting = dao.rowsIn(ConversationBucket.WAITING).map { it.toDomain() },
                assignedToMe = dao.rowsIn(ConversationBucket.ASSIGNED_TO_ME).map { it.toDomain() },
            )
        }

        override suspend fun write(queue: ConversationQueue) {
            val rows =
                queue.waiting.map { it.toEntity(ConversationBucket.WAITING) } +
                    queue.assignedToMe.map { it.toEntity(ConversationBucket.ASSIGNED_TO_ME) }
            dao.replaceAll(rows)
        }
    }

private fun ConversationRowEntity.toDomain() =
    ConversationSummary(
        conversationId = conversationId,
        visitorId = visitorId,
        emojiCreature = emojiCreature,
        emojiFood = emojiFood,
        visitorName = visitorName,
        createdAt = createdAt,
        operatorUnreadCount = operatorUnreadCount,
    )

private fun ConversationSummary.toEntity(bucket: String) =
    ConversationRowEntity(
        conversationId = conversationId,
        bucket = bucket,
        visitorId = visitorId,
        emojiCreature = emojiCreature,
        emojiFood = emojiFood,
        visitorName = visitorName,
        createdAt = createdAt,
        operatorUnreadCount = operatorUnreadCount,
    )
