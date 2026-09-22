package ago.chat.android.data.thread

import ago.chat.android.core.domain.conversations.ComposerDraftStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-15`: [ComposerDraftStore] over Room - the concrete half of the port that interface's own doc
 * comment describes, the same split [ago.chat.android.data.conversations.RoomConversationListCache]
 * already draws for the queue cache.
 */
@Singleton
internal class RoomComposerDraftStore
    @Inject
    constructor(
        private val dao: ComposerDraftDao,
    ) : ComposerDraftStore {
        override suspend fun read(conversationId: String): String? = dao.draftFor(conversationId)

        override suspend fun write(
            conversationId: String,
            draft: String,
        ) {
            dao.upsert(ComposerDraftEntity(conversationId, draft))
        }

        override suspend fun clear(conversationId: String) {
            dao.deleteFor(conversationId)
        }
    }
