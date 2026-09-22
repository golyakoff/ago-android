package ago.chat.android.data

import ago.chat.android.data.conversations.ConversationCacheWrittenEntity
import ago.chat.android.data.conversations.ConversationRowDao
import ago.chat.android.data.conversations.ConversationRowEntity
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * `26-14`: Room's first database in this app — see `docs/architecture.md` "Offline" and
 * [ago.chat.android.core.domain.conversations.ConversationListCache]'s own doc comment for what it
 * caches and why. Lives in `:app` because building one needs a `Context`
 * (`di/AppModule.provideAgoChatDatabase`), the same "the concrete technology is wired where Android
 * itself is wired" boundary every other `:app`-only class in this package draws.
 *
 * `exportSchema = false`: no `room.schemaLocation` is configured, and this item does not add one — a
 * schema history matters once this database has a real migration to get right, which is the day a
 * second table or a second version number actually arrives, not before.
 */
@Database(
    entities = [ConversationRowEntity::class, ConversationCacheWrittenEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class AgoChatDatabase : RoomDatabase() {
    abstract fun conversationRowDao(): ConversationRowDao
}
