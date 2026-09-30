package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-332`: [SetupWizardViewModel]'s whole state — the identical four-arm Loading/Loaded/NotConfigured/Failed
 * shape [ReadinessUiState] establishes, since the wizard reads the identical booking-readiness chain. The
 * one difference is [Loaded]: it carries the *derived* [SetupWizardStep] rather than the raw calendar list,
 * because the wizard shows one step at a time, not the whole chain the Готовность panel draws.
 */
internal sealed interface SetupWizardUiState {
    data object Loading : SetupWizardUiState

    /**
     * @param step the step the tenant is on — [deriveSetupWizardStep] over the same readiness chain the
     *   Готовность panel renders, plus [SetupWizardViewModel]'s own trigger-word read.
     * @param calendarName the first calendar's own name for context, or `null` when no calendar exists yet
     *   ([SetupWizardStep.CreateCalendar]) — [ago.chat.android.core.domain.readiness.CalendarReadiness]'s
     *   own `calendarName == null` placeholder.
     * @param triggerWord the trigger word to show in the «Готово» message — the first configured word, or
     *   the default `/записаться` when none is set yet (mirrors `ago-console`'s own `DEFAULT_TRIGGER_WORD`).
     */
    data class Loaded(
        val step: SetupWizardStep,
        val calendarName: String?,
        val triggerWord: String,
    ) : SetupWizardUiState

    /** This deployment does not run AGO Calendar at all — the identical honest "not configured" state
     * [ReadinessUiState.NotConfigured] draws, never a spinner and never a failure. */
    data object NotConfigured : SetupWizardUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : SetupWizardUiState
}
