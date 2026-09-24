package ago.chat.android.devices

import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-19`: [channelImportanceIsOn] proven against every importance value Android itself defines, on a
 * plain JVM - no real `NotificationManager` needed, since Android's own `IMPORTANCE_*` constants are
 * plain `Int`s [NotificationChannelStateReader]'s real implementation reads and hands here unchanged.
 */
class NotificationChannelStateTest {
    @Test
    fun `IMPORTANCE_NONE - the operator turned the channel off - reads as off`() {
        assertFalse(channelImportanceIsOn(NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun `every other named importance level reads as on`() {
        assertTrue(channelImportanceIsOn(NotificationManager.IMPORTANCE_MIN))
        assertTrue(channelImportanceIsOn(NotificationManager.IMPORTANCE_LOW))
        assertTrue(channelImportanceIsOn(NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue(channelImportanceIsOn(NotificationManager.IMPORTANCE_HIGH))
        assertTrue(channelImportanceIsOn(NotificationManager.IMPORTANCE_MAX))
    }

    @Test
    fun `a channel not created yet - IMPORTANCE_UNSPECIFIED - reads as on`() {
        // `ensureChannelsCreated` runs at every process start, so this case is only ever expected before
        // that has run once - never in steady state - and defaulting to "on" matches
        // `NotificationManager.IMPORTANCE_DEFAULT`, the importance `ensureChannelsCreated` actually
        // creates both channels at.
        assertTrue(channelImportanceIsOn(NotificationManagerCompat.IMPORTANCE_UNSPECIFIED))
    }
}
