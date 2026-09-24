package ago.chat.android.data.conversations

import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-14`: [ConversationListCache] over Room — the concrete half of the port that interface's own doc
 * comment describes, the same split `AgoActiveSite`/`SessionStore` already draw for
 * `EncryptedSharedPreferences`. Every translation between `:core:domain`'s wire-shaped
 * [ConversationSummary] and this module's `@Entity` lives here and nowhere else, so a Room annotation
 * never has to appear on a `:core:domain` type.
 *
 * `26-46`: also [ConversationsUnreadTotal] — the identical class, a second small interface, rather than
 * a second class wrapping the same [dao]. `@Singleton` already makes this the one instance Hilt ever
 * builds ([ago.chat.android.di.AppModule.provideConversationListCache]'s own binding), so a second
 * `@Singleton` class here would just be a second handle onto the same [ConversationRowDao] for no
 * reason - one class, two ports, is the plainer shape when both already share every dependency they
 * have.
 */
@Singleton
internal class RoomConversationListCache
    @Inject
    constructor(
        private val dao: ConversationRowDao,
    ) : ConversationListCache,
        ConversationsUnreadTotal {
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

        /** [ConversationsUnreadTotal.observeTotal]'s own contract: `null` until [write] has ever run,
         * the sum of «Мои»'s own `operatorUnreadCount` column after that - never [read]'s own
         * once-only snapshot, because this has to keep emitting for as long as
         * [ago.chat.android.shell.AppShellViewModel] is collecting it. */
        override fun observeTotal(): Flow<Int?> =
            combine(
                dao.observeHasEverBeenWritten(),
                dao.observeTotalUnreadIn(ConversationBucket.ASSIGNED_TO_ME),
            ) { everWritten, total -> if (everWritten == 0) null else total }
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
