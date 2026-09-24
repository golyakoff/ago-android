package ago.chat.android.conversations

import ago.chat.android.devices.ConversationRefreshSignal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-61`: a plain JVM test against [ConversationListForegroundRefreshTrigger.onStart] directly, with no
 * real `ProcessLifecycleOwner` in play - the identical shape
 * `ago.chat.android.realtime.OperatorHubConnectionLifecycleTest`'s own doc comment states for the
 * identical reason: [ConversationListForegroundRefreshTrigger.start] is the only member that reads one,
 * and [ConversationListForegroundRefreshTrigger.onStart] never reads its own `owner` parameter, so a
 * [FakeLifecycleOwner] whose [Lifecycle] is never touched is enough.
 *
 * What this class actually has to prove - the rotation guarantee itself (`ProcessLifecycleOwner` never
 * firing `onStart` for an `Activity` recreation) is not this class's own to prove; it is the same
 * Android-framework guarantee [ago.chat.android.realtime.OperatorHubConnectionLifecycle] and
 * [ago.chat.android.devices.ProcessLifecycleForegroundTracker] already rely on with no unit test of their
 * own - is narrower: that the very first `onStart` (cold process start) is not mistaken for a foreground
 * *return*, and that every one after it is.
 */
class ConversationListForegroundRefreshTriggerTest {
    private val owner = FakeLifecycleOwner()

    @Test
    fun `the first onStart is the process launching, not a return - no refresh is requested`() {
        val signal = FakeConversationRefreshSignal()
        val trigger = ConversationListForegroundRefreshTrigger(signal)

        trigger.onStart(owner)

        assertEquals(0, signal.requestCalls)
    }

    @Test
    fun `a later onStart is a genuine return from the background - a refresh is requested`() {
        val signal = FakeConversationRefreshSignal()
        val trigger = ConversationListForegroundRefreshTrigger(signal)

        trigger.onStart(owner)
        trigger.onStart(owner)

        assertEquals(1, signal.requestCalls)
    }

    @Test
    fun `every foreground return after the first requests its own refresh`() {
        val signal = FakeConversationRefreshSignal()
        val trigger = ConversationListForegroundRefreshTrigger(signal)

        trigger.onStart(owner)
        trigger.onStart(owner)
        trigger.onStart(owner)
        trigger.onStart(owner)

        assertEquals(3, signal.requestCalls)
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = throw UnsupportedOperationException("not exercised here - onStart never reads its own owner")
    }

    private class FakeConversationRefreshSignal : ConversationRefreshSignal {
        private val mutableRefreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val refreshRequests: SharedFlow<Unit> = mutableRefreshRequests

        var requestCalls: Int = 0
            private set

        override fun requestRefresh() {
            requestCalls++
            mutableRefreshRequests.tryEmit(Unit)
        }
    }
}
