package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-187`: [resolveAutostartUiState] proven as a plain function over three explicit inputs - no Android, no
 * `DataStore`, no clock - the identical split [AutostartBootInferenceTest] already draws one layer down, for
 * the boot-signal half of this same row.
 *
 * **Fails-before / passes-after.** Before this item, the row's title came straight from the manufacturer
 * guess ([DeviceModeStatus.NeedsAttention] → «Автозапуск: Выключено», a fact Android has no API to confirm -
 * `26-128`'s own [AutostartAdvisor] doc comment). `restrictive guess with no boot signal reads Recommended,
 * never Blocked` below is the test that fails against that old behaviour (which had no `Recommended` state
 * at all - only the binary [DeviceModeStatus] the guess itself already carried) and passes once
 * [resolveAutostartUiState] exists: a restrictive guess with nothing to confirm or deny it must land on the
 * neutral middle state, never on [AutostartUiState.Blocked] (a false claim of a *reboot observation* that
 * never happened) and never silently agree with a guess strong enough to read as fact.
 */
class AutostartUiStateTest {
    @Test
    fun `restrictive guess with no boot signal reads Recommended, never Blocked`() {
        assertEquals(
            AutostartUiState.Recommended,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.NeedsAttention,
                bootSignal = AutostartBootSignal.NoSignal,
                manuallyConfirmed = false,
            ),
        )
    }

    @Test
    fun `non-restrictive guess with no boot signal reads Confirmed`() {
        assertEquals(
            AutostartUiState.Confirmed,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.Ok,
                bootSignal = AutostartBootSignal.NoSignal,
                manuallyConfirmed = false,
            ),
        )
    }

    @Test
    fun `a Blocked boot signal wins over a favourable guess`() {
        assertEquals(
            AutostartUiState.Blocked,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.Ok,
                bootSignal = AutostartBootSignal.Blocked,
                manuallyConfirmed = false,
            ),
        )
    }

    @Test
    fun `a Blocked boot signal wins over an existing manual confirmation`() {
        // `26-187`'s own precedence: a real, unfavourable observation outranks the operator's own earlier
        // claim - this function's own contract only decides the *state*; clearing the persisted flag on
        // disk is the caller's job (see SettingsViewModel.reloadAutostartUiState).
        assertEquals(
            AutostartUiState.Blocked,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.NeedsAttention,
                bootSignal = AutostartBootSignal.Blocked,
                manuallyConfirmed = true,
            ),
        )
    }

    @Test
    fun `an AutostartConfirmed boot signal wins over a restrictive guess`() {
        assertEquals(
            AutostartUiState.Confirmed,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.NeedsAttention,
                bootSignal = AutostartBootSignal.AutostartConfirmed,
                manuallyConfirmed = false,
            ),
        )
    }

    @Test
    fun `manual confirmation turns a restrictive guess green when there is no boot signal`() {
        assertEquals(
            AutostartUiState.Confirmed,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.NeedsAttention,
                bootSignal = AutostartBootSignal.NoSignal,
                manuallyConfirmed = true,
            ),
        )
    }

    @Test
    fun `clearing the manual confirmation with a restrictive guess and no boot signal falls back to Recommended`() {
        assertEquals(
            AutostartUiState.Recommended,
            resolveAutostartUiState(
                recommendation = DeviceModeStatus.NeedsAttention,
                bootSignal = AutostartBootSignal.NoSignal,
                manuallyConfirmed = false,
            ),
        )
    }
}
