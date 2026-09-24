package ago.chat.android.devices

import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: everything [AgoPushMessagingService.onMessageReceived]/[AgoPushMessagingService
 * .onDeletedMessages] decide, pulled out of the `Service` itself - the identical split
 * [DeviceRegistrationCoordinator] already is for `onNewToken` (that class's own doc comment: "one class
 * rather than three call sites each doing their own thing"), applied to the receive path instead of the
 * registration one. A plain class with an `@Inject` constructor and no Android type in its own signature -
 * every dependency is one of this item's own small interfaces - which is what makes this the one place a
 * plain JVM test can exercise "dedupe, then decide, then present" as a sequence, with no `Service`, no
 * `RemoteMessage` and no real `NotificationManager` anywhere in the test.
 *
 * `26-19` adds a fourth check, [QuietHoursSettings.suppressesAt] - the identical kind of gate
 * [decideAlert] already is (a decision made from live state at the moment a push arrives, not a side
 * effect buried in [PushNotificationPresenter]), so it sits in this same named sequence rather than being
 * folded into the presenter. It runs *after* [decideAlert] and *after* dedupe, matching this class's own
 * existing rule that a suppressed push still counts as seen: a redelivery arriving once quiet hours has
 * ended must not surface the original push late just because dedupe never got the chance to record it.
 */
@Singleton
public class IncomingPushRouter
    @Inject
    constructor(
        private val dedupeStore: PushMessageDedupeStore,
        private val openConversationTracker: OpenConversationTracker,
        private val appForegroundTracker: AppForegroundTracker,
        private val notificationPresenter: PushNotificationPresenter,
        private val refreshSignal: ConversationRefreshSignal,
        private val quietHoursPreferences: QuietHoursPreferences,
        private val clock: LocalClock,
    ) {
        /**
         * `onMessageReceived`'s own decision, in order: parse, dedupe, decide, quiet hours, present. Each
         * step can return early, and each early return is a real, named outcome rather than a fallthrough:
         *
         * 1. [parseIncomingPush] returning `null` - a payload this version does not understand
         *    ([IncomingPush]'s own doc comment on why that is silently ignored rather than thrown).
         * 2. [PushMessageDedupeStore.markSeenIfNew] returning `false` - a redelivery of a message already
         *    seen, which must "render nothing new" regardless of whether the first delivery was itself
         *    shown or suppressed (`docs/backlog/26-18-*.md`'s own Done-when box, checked *before*
         *    [decideAlert] rather than after: a message the operator was already looking at the first time
         *    is still a message already seen the second time).
         * 3. [decideAlert] returning `false` - the operator is already looking at this exact conversation
         *    right now.
         * 4. [QuietHoursSettings.suppressesAt] returning `true` - the operator's own quiet-hours window,
         *    read fresh on every message (`26-19`'s own Scope: "client-side only", never cached beyond the
         *    single [QuietHoursPreferences.settings] read below).
         *
         * [providerMessageId] is `RemoteMessage.messageId` (nullable on the SDK's own type, despite its
         * documentation describing the field as always present) - see [PushMessageDedupeStore]'s own doc
         * comment for why this, and not the domain `messageId` [IncomingPush.VisitorMessage] does not even
         * carry, is the dedupe key. [dedupeKeyFor] below is what a `null` [providerMessageId] falls back
         * to, built from [data] itself rather than skipping dedupe entirely for that case.
         */
        public suspend fun handleMessage(
            providerMessageId: String?,
            data: Map<String, String>?,
        ) {
            val event = parseIncomingPush(data.orEmpty()) ?: return

            val dedupeKey = providerMessageId?.let { "provider:$it" } ?: dedupeKeyFor(data.orEmpty())
            val isNew = dedupeStore.markSeenIfNew(dedupeKey)
            if (!isNew) return

            val shouldAlert =
                decideAlert(
                    conversationId = event.conversationId,
                    openConversationId = openConversationTracker.currentConversationId,
                    appInForeground = appForegroundTracker.isAppInForeground,
                )
            if (!shouldAlert) return

            val quietHours = quietHoursPreferences.settings.first()
            if (quietHours.suppressesAt(clock.currentMinuteOfDay())) return

            notificationPresenter.present(event)
        }

        /**
         * `onDeletedMessages()`'s own job - RuStore calls it when one or more pushes were **not**
         * delivered (TTL expiry being its own named example), and its own documentation recommends
         * syncing with the server rather than assuming nothing was missed. There is no "which conversation"
         * to name here - the callback carries none - so the honest recovery is the same one a manual
         * pull-to-refresh already performs: re-ask for the truth.
         */
        public fun handleDeletedMessages() {
            refreshSignal.requestRefresh()
        }

        /**
         * The fallback dedupe key for the (undocumented, never yet observed) case where RuStore hands this
         * app a `RemoteMessage` with no `messageId` of its own. Built from fields the fan-out itself always
         * sends rather than the map's own `toString()` - a `HashMap`'s iteration order is not a contract,
         * even though it tends to be stable in practice for an identical key set on one JVM.
         */
        private fun dedupeKeyFor(data: Map<String, String>): String =
            "data:${data[KEY_CONVERSATION_ID].orEmpty()}:${data[KEY_MESSAGE_ID].orEmpty()}"
    }
