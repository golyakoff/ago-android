package ago.chat.android.data.thread

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** `26-15`: plain suspend functions, the same "read-then-write, never observed as a stream" shape
 * [ConversationRowDao][ago.chat.android.data.conversations.ConversationRowDao] already establishes. */
@Dao
internal interface ComposerDraftDao {
    @Query("SELECT draft FROM composer_drafts WHERE conversationId = :conversationId")
    suspend fun draftFor(conversationId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ComposerDraftEntity)

    @Query("DELETE FROM composer_drafts WHERE conversationId = :conversationId")
    suspend fun deleteFor(conversationId: String)
}
