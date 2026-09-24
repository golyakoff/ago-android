package ago.chat.android.devices

import ago.chat.android.di.DeviceDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: client-side dedupe by the payload's own message id -
 * `docs/architecture/push-notifications.md`'s own "Idempotency, without an inbox row": "Client-side
 * dedupe by `MessageId`... `RemoteMessage.messageId` also exists as a provider-assigned id". This is
 * that second, provider-assigned field ([IncomingPushRouter]'s own doc comment states why, over the
 * domain `messageId` [IncomingPush.VisitorMessage] itself does not even carry) - present on **every**
 * `RemoteMessage`, assignment or visitor-message alike, unlike the domain `messageId` key which only
 * `HandleMessageAsync`'s own payload carries at all.
 *
 * **Persisted, not merely in-memory.** `AgoPushMessagingService`'s own doc comment: every method on it
 * "runs on a background thread already", and RuStore's own distributor can start a fresh process for a
 * burst of deliveries - an in-memory `Set` would forget everything the moment that process is not the one
 * handling the next call. A phone that was offline while several pushes queued and receives them together
 * on reconnect (this item's own Done-when: "the phone was offline while all four were sent and receives
 * them together") is exactly the burst shape most likely to span more than one process lifetime, which is
 * the case a purely in-memory set would silently stop deduplicating across.
 *
 * **`@DeviceDataStore`, not a file of its own.** The identical file [DataStoreInstallationId] already
 * writes to - both are small, non-secret, install-scoped values with no reason to survive
 * `SessionStore.clear()`'s own sign-out wipe (this app's seen-ids history is a client-side delivery
 * optimisation, not session state) and no reason for a second on-disk file to exist for it.
 *
 * **Bounded, and the bound is a plain trim, not an eviction policy with a clock.** [CAPACITY] recent ids
 * is enough to survive the burst shape this item names explicitly (four queued messages) many times over,
 * and trimming the *oldest* half of a plain delimited list costs nothing worth measuring against the size
 * this file would otherwise grow to for ever. There is no reason to reach for a time-based expiry when a
 * bounded count already answers "how much can this ever cost".
 */
public interface PushMessageDedupeStore {
    /**
     * Records [key] as seen and reports whether it is new: `true` means proceed (this is the first time
     * this exact key has been seen, or it aged out of the bounded window long enough that this app can no
     * longer tell), `false` means a redelivery - `IncomingPushRouter`'s own caller renders nothing for it.
     *
     * Deciding and writing in the same [DataStore.edit] transform, not a separate read then a separate
     * write - [DataStoreInstallationId]'s own doc comment states why that ordering is what makes two
     * concurrent callers against the same key agree, rather than each independently reading "not seen yet"
     * and both writing their own copy.
     */
    public suspend fun markSeenIfNew(key: String): Boolean
}

@Singleton
public class DataStorePushMessageDedupeStore
    @Inject
    constructor(
        @DeviceDataStore private val store: DataStore<Preferences>,
    ) : PushMessageDedupeStore {
        override suspend fun markSeenIfNew(key: String): Boolean {
            var wasNew = false
            store.edit { preferences ->
                val existing = preferences[KEY]?.split(DELIMITER)?.filter { it.isNotEmpty() } ?: emptyList()
                val result = SeenKeysWindow.apply(existing, key, CAPACITY)
                wasNew = result.isNew
                // Written only when something actually changed - never a same-value re-write for a
                // duplicate key, which is what keeps a redelivery burst from touching the file at all
                // (`SeenKeysWindowTest`'s own doc comment states why this class's real logic is proven
                // there, with no file IO, rather than by hammering this store with many sequential
                // writes).
                if (result.isNew) preferences[KEY] = result.updated.joinToString(DELIMITER)
            }
            return wasNew
        }

        private companion object {
            val KEY: Preferences.Key<String> = stringPreferencesKey("seen_push_message_ids")

            // `,`: every real key this store ever receives is either a RuStore-assigned message id
            // (`RemoteMessage.messageId`, documented alphanumeric) or this app's own fallback key built
            // from a conversation/message id pair (`IncomingPushRouter`'s own doc comment) - neither
            // shape has ever been observed to contain a comma, and there is no live RuStore project to
            // verify the provider's own id format against, so this is a documented assumption rather
            // than a verified one.
            const val DELIMITER = ","

            // Comfortably above the burst this item names by name (four queued messages) with headroom
            // for several such bursts across more than one conversation before the oldest entry is ever
            // evicted.
            const val CAPACITY = 30
        }
    }

/**
 * `26-18`: the pure bookkeeping [DataStorePushMessageDedupeStore] delegates to - "is this key already in
 * the bounded window, and if not, what does the trimmed window look like with it added" is ordinary list
 * arithmetic with no dependency on `DataStore`, a file, or a coroutine at all. Factored out specifically
 * so [SeenKeysWindowTest] can prove the multi-key and capacity-trimming behaviour with **no real file IO
 * whatsoever** - `DataStorePushMessageDedupeStoreTest`'s own doc comment (matching
 * `DataStoreThemePreferencesTest`'s own documented finding) is why more than one real write against the
 * same on-disk file within one test is deliberately avoided in this codebase on this platform, and this
 * split is what makes avoiding it cost nothing: the behaviour worth proving thoroughly is exactly the
 * part that never needed a file to begin with.
 */
internal object SeenKeysWindow {
    internal data class Result(
        val isNew: Boolean,
        val updated: List<String>,
    )

    internal fun apply(
        existing: List<String>,
        key: String,
        capacity: Int,
    ): Result =
        if (key in existing) {
            Result(isNew = false, updated = existing)
        } else {
            Result(isNew = true, updated = (existing + key).takeLast(capacity))
        }
}
