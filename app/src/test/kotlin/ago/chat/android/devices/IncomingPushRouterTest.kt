package ago.chat.android.devices

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-18`: [IncomingPushRouter] on a plain JVM through fakes for every one of its ports - no `Service`,
 * no `RemoteMessage`, no real `NotificationManager` anywhere in this file, the identical shape
 * [DeviceRegistrationCoordinatorTest] already establishes for the registration half of this same
 * `Service`. `26-19` added the last two ([QuietHoursPreferences]/[LocalClock]) for its own fourth check.
 *
 * Every Done-when box `AgoPushMessagingService.onMessageReceived` itself is not the place to prove is
 * proven here instead: the tag/dedupe mechanism (four-messages-collapse's own *logic* half), a
 * redelivery rendering nothing new, and no message body ever reaching [PushNotificationPresenter] (the
 * fakes below never even have a body to leak).
 */
class IncomingPushRouterTest {
    @Test
    fun `a fresh assignment, app backgrounded, is presented`() =
        runTest {
            val presenter = RecordingPresenter()
            val router = routerWith(presenter = presenter)

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1"))

            assertEquals(listOf(IncomingPush.ConversationAssigned("conv-1")), presenter.presented)
        }

    @Test
    fun `a fresh visitor message, app backgrounded, is presented`() =
        runTest {
            val presenter = RecordingPresenter()
            val router = routerWith(presenter = presenter)

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1", "messageId" to "msg-1"))

            assertEquals(listOf(IncomingPush.VisitorMessage("conv-1")), presenter.presented)
        }

    @Test
    fun `a redelivered push - the identical provider messageId - renders nothing new`() =
        runTest {
            val presenter = RecordingPresenter()
            val router = routerWith(presenter = presenter)
            val data = mapOf("conversationId" to "conv-1", "messageId" to "msg-1")

            router.handleMessage("provider-1", data)
            router.handleMessage("provider-1", data)

            assertEquals("only the first delivery is ever presented", 1, presenter.presented.size)
        }

    @Test
    fun `two DIFFERENT provider ids for the same conversation both present - not deduped against each other`() =
        runTest {
            val presenter = RecordingPresenter()
            val router = routerWith(presenter = presenter)

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1", "messageId" to "msg-1"))
            router.handleMessage("provider-2", mapOf("conversationId" to "conv-1", "messageId" to "msg-2"))

            assertEquals(2, presenter.presented.size)
        }

    @Test
    fun `an unparseable payload never reaches the presenter or the dedupe store`() =
        runTest {
            val presenter = RecordingPresenter()
            val dedupe = RecordingDedupeStore()
            val router = routerWith(presenter = presenter, dedupeStore = dedupe)

            router.handleMessage("provider-1", emptyMap())

            assertTrue(presenter.presented.isEmpty())
            assertTrue(
                "a payload this app cannot even parse should never occupy a slot in the bounded dedupe window",
                dedupe.keysSeen.isEmpty(),
            )
        }

    @Test
    fun `the conversation open in front of the operator right now is suppressed - decideAlert's own rule`() =
        runTest {
            val presenter = RecordingPresenter()
            val router =
                routerWith(
                    presenter = presenter,
                    openConversationTracker = FixedOpenConversationTracker("conv-1"),
                    appForegroundTracker = FixedForegroundTracker(true),
                )

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1"))

            assertTrue(presenter.presented.isEmpty())
        }

    @Test
    fun `the identical conversation, app backgrounded, is NOT suppressed`() =
        runTest {
            val presenter = RecordingPresenter()
            val router =
                routerWith(
                    presenter = presenter,
                    openConversationTracker = FixedOpenConversationTracker("conv-1"),
                    appForegroundTracker = FixedForegroundTracker(false),
                )

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1"))

            assertEquals(1, presenter.presented.size)
        }

    @Test
    fun `a suppressed push still counts as seen - a later redelivery is not shown just because the first one was silent`() =
        runTest {
            val presenter = RecordingPresenter()
            val dedupeStore = DataStoreFreeDedupeStore()
            val data = mapOf("conversationId" to "conv-1")

            // First delivery: the operator is looking at this exact conversation right now, so it is
            // suppressed - but still, per [PushMessageDedupeStore]'s own contract, marked seen.
            routerWith(
                presenter = presenter,
                dedupeStore = dedupeStore,
                openConversationTracker = FixedOpenConversationTracker("conv-1"),
                appForegroundTracker = FixedForegroundTracker(true),
            ).handleMessage("provider-1", data)

            // Redelivery of the identical provider id: the operator has since left the conversation and
            // backgrounded the app - a naive implementation that decided *before* deduping would show
            // this one, since [decideAlert] alone would no longer suppress it.
            routerWith(
                presenter = presenter,
                dedupeStore = dedupeStore,
                openConversationTracker = FixedOpenConversationTracker(null),
                appForegroundTracker = FixedForegroundTracker(false),
            ).handleMessage("provider-1", data)

            assertTrue("the redelivery of an already-seen message renders nothing new, suppressed or not", presenter.presented.isEmpty())
        }

    @Test
    fun `no messageId at all falls back to a data-derived key rather than skipping dedupe entirely`() =
        runTest {
            val presenter = RecordingPresenter()
            val dedupeStore = DataStoreFreeDedupeStore()
            val router = routerWith(presenter = presenter, dedupeStore = dedupeStore)
            val data = mapOf("conversationId" to "conv-1", "messageId" to "msg-1")

            router.handleMessage(providerMessageId = null, data = data)
            router.handleMessage(providerMessageId = null, data = data)

            assertEquals("the second, identical, providerless delivery is still deduped", 1, presenter.presented.size)
        }

    @Test
    fun `onDeletedMessages asks for a conversation-list refresh`() {
        val refreshSignal = RecordingRefreshSignal()
        val router = routerWith(refreshSignal = refreshSignal)

        router.handleDeletedMessages()

        assertEquals(1, refreshSignal.requestCalls)
    }

    // -------------------------------------------------------------------------- `26-19`: quiet hours

    @Test
    fun `a push arriving inside quiet hours is suppressed - decideAlert's own rule extended`() =
        runTest {
            val presenter = RecordingPresenter()
            val router =
                routerWith(
                    presenter = presenter,
                    quietHoursPreferences =
                        FixedQuietHoursPreferences(
                            QuietHoursSettings(enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1439),
                        ),
                    clock = FixedLocalClock(minuteOfDay = 12 * 60),
                )

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1"))

            assertTrue("quiet hours suppress the push entirely - no notification, silent or otherwise", presenter.presented.isEmpty())
        }

    @Test
    fun `a push arriving outside quiet hours is presented normally`() =
        runTest {
            val presenter = RecordingPresenter()
            val router =
                routerWith(
                    presenter = presenter,
                    quietHoursPreferences =
                        FixedQuietHoursPreferences(
                            QuietHoursSettings(
                                enabled = true,
                                startMinuteOfDay = 22 * 60,
                                endMinuteOfDay =
                                    7 * 60,
                            ),
                        ),
                    clock = FixedLocalClock(minuteOfDay = 12 * 60),
                )

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1"))

            assertEquals(1, presenter.presented.size)
        }

    @Test
    fun `quiet hours disabled never suppresses, even at a minute a range would otherwise cover`() =
        runTest {
            val presenter = RecordingPresenter()
            val router =
                routerWith(
                    presenter = presenter,
                    quietHoursPreferences =
                        FixedQuietHoursPreferences(
                            QuietHoursSettings(enabled = false, startMinuteOfDay = 0, endMinuteOfDay = 1439),
                        ),
                    clock = FixedLocalClock(minuteOfDay = 12 * 60),
                )

            router.handleMessage("provider-1", mapOf("conversationId" to "conv-1"))

            assertEquals(1, presenter.presented.size)
        }

    @Test
    fun `a push suppressed by quiet hours still counts as seen - a later redelivery once quiet hours end is not shown late`() =
        runTest {
            val presenter = RecordingPresenter()
            val dedupeStore = DataStoreFreeDedupeStore()
            val data = mapOf("conversationId" to "conv-1")

            // First delivery: quiet hours are active, so it is suppressed - but still marked seen, per
            // `PushMessageDedupeStore`'s own contract (`IncomingPushRouterTest`'s own identical case for
            // `decideAlert`, extended to this fourth check).
            routerWith(
                presenter = presenter,
                dedupeStore = dedupeStore,
                quietHoursPreferences =
                    FixedQuietHoursPreferences(
                        QuietHoursSettings(enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1439),
                    ),
                clock = FixedLocalClock(minuteOfDay = 12 * 60),
            ).handleMessage("provider-1", data)

            // Redelivery of the identical provider id, now that quiet hours have ended: a naive
            // implementation that decided *before* deduping would show this one.
            routerWith(
                presenter = presenter,
                dedupeStore = dedupeStore,
                quietHoursPreferences = FixedQuietHoursPreferences(QuietHoursSettings(enabled = false)),
                clock = FixedLocalClock(minuteOfDay = 12 * 60),
            ).handleMessage("provider-1", data)

            assertTrue(
                "the redelivery of an already-seen message renders nothing new, suppressed by quiet hours or not",
                presenter.presented.isEmpty(),
            )
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun routerWith(
        dedupeStore: PushMessageDedupeStore = DataStoreFreeDedupeStore(),
        openConversationTracker: OpenConversationTracker = FixedOpenConversationTracker(null),
        appForegroundTracker: AppForegroundTracker = FixedForegroundTracker(false),
        presenter: PushNotificationPresenter = RecordingPresenter(),
        refreshSignal: ConversationRefreshSignal = RecordingRefreshSignal(),
        quietHoursPreferences: QuietHoursPreferences = FixedQuietHoursPreferences(QuietHoursSettings(enabled = false)),
        clock: LocalClock = FixedLocalClock(minuteOfDay = 0),
    ): IncomingPushRouter =
        IncomingPushRouter(
            dedupeStore = dedupeStore,
            openConversationTracker = openConversationTracker,
            appForegroundTracker = appForegroundTracker,
            notificationPresenter = presenter,
            refreshSignal = refreshSignal,
            quietHoursPreferences = quietHoursPreferences,
            clock = clock,
        )

    private class RecordingPresenter : PushNotificationPresenter {
        val presented = mutableListOf<IncomingPush>()

        override fun present(event: IncomingPush) {
            presented += event
        }
    }

    private class DataStoreFreeDedupeStore : PushMessageDedupeStore {
        private val seen = mutableSetOf<String>()

        override suspend fun markSeenIfNew(key: String): Boolean = seen.add(key)
    }

    private class RecordingDedupeStore : PushMessageDedupeStore {
        val keysSeen = mutableListOf<String>()

        override suspend fun markSeenIfNew(key: String): Boolean {
            keysSeen += key
            return true
        }
    }

    private class FixedOpenConversationTracker(
        override val currentConversationId: String?,
    ) : OpenConversationTracker {
        override fun conversationOpened(conversationId: String) = error("not exercised by this router")

        override fun conversationClosed(conversationId: String) = error("not exercised by this router")
    }

    private class FixedForegroundTracker(
        override val isAppInForeground: Boolean,
    ) : AppForegroundTracker

    private class RecordingRefreshSignal : ConversationRefreshSignal {
        override val refreshRequests: SharedFlow<Unit> =
            MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var requestCalls: Int = 0
            private set

        override fun requestRefresh() {
            requestCalls++
        }
    }

    private class FixedQuietHoursPreferences(
        settings: QuietHoursSettings,
    ) : QuietHoursPreferences {
        override val settings: Flow<QuietHoursSettings> = flowOf(settings)

        override suspend fun setSettings(settings: QuietHoursSettings) = error("not exercised by this router")
    }

    private class FixedLocalClock(
        private val minuteOfDay: Int,
    ) : LocalClock {
        override fun currentMinuteOfDay(): Int = minuteOfDay
    }
}
