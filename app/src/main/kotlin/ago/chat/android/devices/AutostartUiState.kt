package ago.chat.android.devices

/**
 * `26-187`: the «Автозапуск» row's own three-state truth — deliberately a *different* type from
 * [DeviceModeStatus], the two-state enum «Режим работы» still uses unchanged. That row reads a real
 * [BatteryOptimizationChecker] system call and only ever has two honest answers; this row has never had a
 * real system read to begin with ([AutostartAdvisor]'s own doc comment), and folding its extra "we are only
 * guessing" case into [DeviceModeStatus] would give the battery row a third state it can never occupy, just
 * to let this row borrow a name.
 *
 * The bug this fixes: `autostartRecommendationFor` returns [DeviceModeStatus.NeedsAttention] for every
 * Xiaomi/Huawei/Oppo/vivo/Realme phone *unconditionally*, and the row used to render that guess with the
 * same title («Автозапуск: Выключено») a real reading would use — asserting a fact Android has no API to
 * confirm ([AutostartAdvisor]'s own doc comment states the constraint). An operator who had already turned
 * autostart on saw that false "Off" until the *next* reboot produced [AutostartBootSignal.AutostartConfirmed]
 * — nothing short of a reboot could clear it. [Recommended] replaces the false "Off" with an honest "we
 * recommend checking", and [Confirmed]'s manual-confirmation branch gives a correctly-configured operator a
 * way to clear the warning immediately, without waiting for a reboot that may never come.
 */
public enum class AutostartUiState {
    /** Green. Either a real observation (the boot receiver ran) or the operator's own manual confirmation
     * say autostart is on — never merely a manufacturer guess that happens to be favourable. */
    Confirmed,

    /** Neutral, never red. A restrictive-OEM guess with no real observation either way — the honest
     * "we cannot confirm this, please check" [AutostartAdvisor] was always limited to, now drawn as its own
     * state instead of borrowing [Confirmed]'s or a red "Off" it never earned. */
    Recommended,

    /** Red. The one real, unfavourable observation this row can make: the phone rebooted and the app's own
     * `BOOT_COMPLETED` receiver did not run this boot ([AutostartBootSignal.Blocked]). Outranks everything
     * else, including a manual confirmation — see [resolveAutostartUiState]'s own precedence. */
    Blocked,
}

/**
 * `26-187`: the whole precedence decision as one pure function over three explicit inputs, so a plain JUnit
 * test drives every branch with no Android, no `DataStore` and no clock in play — the identical split
 * [inferAutostartBootSignal] already draws one layer down, for the boot-signal half of this same row.
 *
 * Highest precedence first:
 * 1. [AutostartBootSignal.Blocked] — a real, unfavourable observation. Overrides a manual confirmation too;
 *    callers are expected to persist the clear this precedence implies (this function only decides the
 *    *state*, never touches storage — the identical "pure decision, storage stays with the caller" split
 *    [AutostartInferenceReader] itself was already, one layer up).
 * 2. [AutostartBootSignal.AutostartConfirmed] — a real, favourable observation.
 * 3. [manuallyConfirmed] — the operator's own claim, trusted once boot evidence neither confirms nor denies it.
 * 4. [recommendation] == [DeviceModeStatus.NeedsAttention] with none of the above — the restrictive-OEM
 *    guess, with nothing to either confirm or override it: [AutostartUiState.Recommended], not a false "Off".
 * 5. Otherwise — a non-restrictive OEM's guess, or any state that fell through with nothing to flag:
 *    [AutostartUiState.Confirmed].
 */
public fun resolveAutostartUiState(
    recommendation: DeviceModeStatus,
    bootSignal: AutostartBootSignal,
    manuallyConfirmed: Boolean,
): AutostartUiState =
    when {
        bootSignal == AutostartBootSignal.Blocked -> AutostartUiState.Blocked
        bootSignal == AutostartBootSignal.AutostartConfirmed -> AutostartUiState.Confirmed
        manuallyConfirmed -> AutostartUiState.Confirmed
        recommendation == DeviceModeStatus.NeedsAttention -> AutostartUiState.Recommended
        else -> AutostartUiState.Confirmed
    }
