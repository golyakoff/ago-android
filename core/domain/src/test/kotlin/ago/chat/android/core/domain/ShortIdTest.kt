package ago.chat.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A plain JVM unit test — no Android test runner, no Robolectric, because `:core:domain` has no
 * Android dependency to need either of them (`ago-android/docs/architecture.md`, "Testing").
 */
public class ShortIdTest {
    @Test
    public fun `truncates a full GUID to its first eight characters`() {
        assertEquals("wrong-expected-value", shortId("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
    }

    @Test
    public fun `returns a string of eight characters or fewer unchanged`() {
        assertEquals("abc123", shortId("abc123"))
    }
}
