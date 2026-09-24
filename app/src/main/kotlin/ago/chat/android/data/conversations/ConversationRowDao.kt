package ago.chat.android.data.conversations

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * `26-14`: plain suspend functions throughout — [ConversationListCache]'s own doc comment explains why
 * this cache is read-then-write rather than observed as a `Flow`.
 *
 * `26-46` adds the two `Flow`-returning queries below, without contradicting that reasoning:
 * [ConversationListCache] itself (the screen's own read-then-write contract) is untouched, and the new
 * queries back a *different* port ([ConversationsUnreadTotal]) that a genuinely different caller
 * ([ago.chat.android.shell.AppShellViewModel], which does need to observe the disk — it has no live hub
 * connection of its own to fall back on) needs for a genuinely different reason. Room's own
 * `InvalidationTracker` is what makes these live: every [ConversationRowDao.replaceAll] call touches
 * `conversation_rows`, and Room re-runs any open `Flow<T>` query over a table it just wrote to
 * automatically — free "this updated live" behaviour, not a mechanism this app hand-rolls.
 */
@Dao
internal interface ConversationRowDao {
    @Query("SELECT * FROM conversation_rows WHERE bucket = :bucket")
    suspend fun rowsIn(bucket: String): List<ConversationRowEntity>

    @Query("SELECT COUNT(*) FROM conversation_cache_written")
    suspend fun hasEverBeenWritten(): Int

    /** [hasEverBeenWritten]'s own live twin — [RoomConversationListCache.observeTotal]'s own doc comment
     * on why this needs a second, `Flow`-returning query rather than reusing the suspend one above. */
    @Query("SELECT COUNT(*) FROM conversation_cache_written")
    fun observeHasEverBeenWritten(): Flow<Int>

    /** The sum a bucket's own rows carry — the identical `operatorUnreadCount` column
     * [ConversationListViewModel.toRowUi] reads per row, added up by the database itself so this can
     * never drift from what the row's own `UnreadBadge` renders ([ConversationsUnreadTotal]'s own doc
     * comment states why that matters). `COALESCE(..., 0)`: `SUM` over zero rows is SQL `NULL`, and an
     * empty «Мои» is a real `0`, never an absent answer. */
    @Query("SELECT COALESCE(SUM(operatorUnreadCount), 0) FROM conversation_rows WHERE bucket = :bucket")
    fun observeTotalUnreadIn(bucket: String): Flow<Int>

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
