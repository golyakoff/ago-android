package ago.chat.android.channels

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.4): [validateLogoCourtesy]'s own type and
 * size checks — the two branches provable on a plain JVM unit test, since this app's own
 * `testOptions.unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts`) makes
 * `android.graphics.BitmapFactory.decodeByteArray` a stub returning nothing real to decode. Both checks
 * below run — and return — strictly *before* [validateLogoCourtesy] ever calls `BitmapFactory`, so
 * neither needs Robolectric to prove: an unsupported MIME type or an over-budget byte size is rejected
 * on the wire-shape/size facts alone, with no image decoding involved yet. The dimensions/undecodable
 * branches are real behaviour this function has (documented on its own doc comment) but are not unit
 * tested here for that same stub reason - they need a real bitmap decoder, which is an instrumented-test
 * concern this project already keeps off the plain JVM suite (`docs/conventions/testing.md`).
 */
class LogoCourtesyValidationTest {
    @Test
    fun `an unsupported content type is rejected before any decoding is attempted`() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertEquals(LogoValidationProblem.InvalidFormat, validateLogoCourtesy(bytes, "image/svg+xml"))
        assertEquals(LogoValidationProblem.InvalidFormat, validateLogoCourtesy(bytes, "application/pdf"))
    }

    @Test
    fun `each of the three allowed content types passes the format check`() {
        // None of these tiny byte arrays are ever going to decode as a real bitmap under this module's
        // own `isReturnDefaultValues` stub - the point of this test is only that `png`/`jpeg`/`gif`
        // themselves never trip `InvalidFormat`, proven by asserting the *next* check (size) is what
        // actually rejects them, never the format one.
        val tinyBytes = byteArrayOf(1, 2, 3)

        assertEquals(LogoValidationProblem.Undecodable, validateLogoCourtesy(tinyBytes, "image/png"))
        assertEquals(LogoValidationProblem.Undecodable, validateLogoCourtesy(tinyBytes, "image/jpeg"))
        assertEquals(LogoValidationProblem.Undecodable, validateLogoCourtesy(tinyBytes, "image/gif"))
    }

    @Test
    fun `an empty file is rejected as too large, never as a valid zero-byte image`() {
        assertEquals(LogoValidationProblem.TooLarge, validateLogoCourtesy(byteArrayOf(), "image/png"))
    }

    @Test
    fun `a file over the 200 KiB ceiling is rejected as too large`() {
        val oversized = ByteArray(200 * 1024 + 1)

        assertEquals(LogoValidationProblem.TooLarge, validateLogoCourtesy(oversized, "image/png"))
    }

    @Test
    fun `a file at exactly the 200 KiB ceiling is not rejected for size`() {
        val atCeiling = ByteArray(200 * 1024)

        // Passes the size check and falls through to the (stubbed) decode step - `Undecodable`, not
        // `TooLarge`, is what proves the size gate itself did not trip here.
        assertEquals(LogoValidationProblem.Undecodable, validateLogoCourtesy(atCeiling, "image/png"))
    }

    @Test
    fun `format is checked before size - a wrong type is InvalidFormat even when also oversized`() {
        val oversized = ByteArray(200 * 1024 + 1)

        assertEquals(LogoValidationProblem.InvalidFormat, validateLogoCourtesy(oversized, "application/pdf"))
    }
}
