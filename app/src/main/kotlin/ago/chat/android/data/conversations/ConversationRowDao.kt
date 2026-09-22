package ago.chat.android.data.conversations

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * `26-14`: plain suspend functions throughout — [ConversationListCache]'s own doc comment explains why
 * this cache is read-then-write rather than observed as a `Flow`.
 */
@Dao
internal interface ConversationRowDao {
    @Query("SELECT * FROM conversation_rows WHERE bucket = :bucket")
    suspend fun rowsIn(bucket: String): List<ConversationRowEntity>

    @Query("SELECT COUNT(*) FROM conversation_cache_written")
    suspend fun hasEverBeenWritten(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ConversationRowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markWritten(marker: ConversationCacheWrittenEntity)

    @Query("DELETE FROM conversation_rows")
    suspend fun clear()

    /**
     * The whole cache, replaced in one transaction — [ConversationListCache.write]'s own contract:
     * this screen's whole answer, never a merge of one row. `@Transaction` on a default (non-abstract)
     * method is Room's documented shape for "run several of this DAO's own queries atomically" without
     * a second class holding the `RoomDatabase` just to call `withTransaction {}`.
     */
    @Transaction
    suspend fun replaceAll(rows: List<ConversationRowEntity>) {
        clear()
        insertAll(rows)
        markWritten(ConversationCacheWrittenEntity())
    }
}
