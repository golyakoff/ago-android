package ago.chat.android.data

import ago.chat.android.data.conversations.ConversationCacheWrittenEntity
import ago.chat.android.data.conversations.ConversationRowDao
import ago.chat.android.data.conversations.ConversationRowEntity
import ago.chat.android.data.thread.ComposerDraftDao
import ago.chat.android.data.thread.ComposerDraftEntity
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
 * second table or a second version number actually arrives.
 *
 * `26-15` is that day: [ComposerDraftEntity] joins the schema, `version` moves to `2`, and
 * `di/AppModule.provideAgoChatDatabase`'s own `fallbackToDestructiveMigration()` is the migration —
 * a deliberate wipe-and-recreate rather than a hand-written `Migration`, acceptable only because
 * `feedback_zero_real_tenants_free_to_change_backend`'s standing fact still holds for this pre-launch
 * app too: the one build in the wild is `26-09`'s own debug APK, carrying nothing more valuable to
 * preserve across an upgrade than a re-fetchable queue cache and a draft nobody has typed yet.
 */
@Database(
    entities = [ConversationRowEntity::class, ConversationCacheWrittenEntity::class, ComposerDraftEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class AgoChatDatabase : RoomDatabase() {
    abstract fun conversationRowDao(): ConversationRowDao

    abstract fun composerDraftDao(): ComposerDraftDao
}
