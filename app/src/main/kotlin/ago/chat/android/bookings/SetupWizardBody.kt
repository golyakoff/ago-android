package ago.chat.android.bookings

import ago.chat.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * `26-332` (`26-318` port): the guided setup wizard's own body — a single step at a time, derived from the
 * booking-readiness chain by [SetupWizardViewModel]/[deriveSetupWizardStep], reusing the identical
 * four-arm [LoadingBody]/[EmptyBody]/[RefusalBody] idiom [CalendarSetupBody]/[ReadinessBody] establish.
 *
 * **This slice leads the tenant, it does not inline the forms.** Each step shows what to do and an «Открыть
 * экран» button that opens the existing config screen that owns the write ([onGoToTab], the identical
 * [BookingsScreen] `onConfigSelected` swap the Готовность panel's «Исправить» already uses). Porting the
 * console's inline forms is a follow-up (`26-332` brief: "implement the core readiness-driven stepper
 * linking to existing screens first, report the inline-forms polish as a follow-up") — and three of them
 * cannot be ported at all until the Android app grows the writes they need (create-service, add-working-
 * hours-rule, set-trigger-words), which is why [SetupWizardStep.BookingTrigger] shows a stated note instead
 * of a button: the app has no trigger-words write to drive one.
 */
@Composable
internal fun SetupWizardBody(
    state: SetupWizardUiState,
    onRetry: () -> Unit,
    onGoToTab: (BookingsTab) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            SetupWizardUiState.Loading -> LoadingBody()
            SetupWizardUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
            is SetupWizardUiState.Failed ->
                RefusalBody(
                    reason = state.reason,
                    onRetry = onRetry,
                    unexpectedMessageRes = R.string.setup_wizard_load_failed_unexpected,
                )

            is SetupWizardUiState.Loaded -> SetupWizardContent(state = state, onGoToTab = onGoToTab)
        }
    }
}

@Composable
private fun SetupWizardContent(
    state: SetupWizardUiState.Loaded,
    onGoToTab: (BookingsTab) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_wizard_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.step != SetupWizardStep.Done) {
            Text(
                text =
                    stringResource(
                        R.string.setup_wizard_step_index,
                        setupWizardStepOrder.indexOf(state.step) + 1,
                        setupWizardStepOrder.size,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(setupWizardStepTitle(state.step)),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )

        Text(
            text = setupWizardStepBody(step = state.step, calendarName = state.calendarName, triggerWord = state.triggerWord),
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val tab = state.step.targetTab()) {
            null ->
                // `26-332`: the two stepless states. [SetupWizardStep.BookingTrigger] has no in-app screen
                // (the app has no trigger-words write yet — a reported gap), so it shows the note above and
                // no button; [SetupWizardStep.Done] is finished, so it shows the «Готово» message and no
                // button either.
                Unit

            else ->
                Button(onClick = { onGoToTab(tab) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.setup_wizard_open_screen))
                }
        }
    }
}

/** The step's own title string resource — the heading of its card. */
private fun setupWizardStepTitle(step: SetupWizardStep): Int =
    when (step) {
        SetupWizardStep.CreateCalendar -> R.string.setup_wizard_create_calendar_title
        SetupWizardStep.AddMaster -> R.string.setup_wizard_add_master_title
        SetupWizardStep.AddService -> R.string.setup_wizard_add_service_title
        SetupWizardStep.WorkingHours -> R.string.setup_wizard_working_hours_title
        SetupWizardStep.ConfirmSchedule -> R.string.setup_wizard_confirm_schedule_title
        SetupWizardStep.Materializing -> R.string.setup_wizard_materializing_title
        SetupWizardStep.BookingTrigger -> R.string.setup_wizard_booking_trigger_title
        SetupWizardStep.Publish -> R.string.setup_wizard_publish_title
        SetupWizardStep.Done -> R.string.setup_wizard_done_title
    }

/** The step's own explanatory body — most are plain strings; [SetupWizardStep.Done] folds the trigger word
 * into its «Готово: напишите …» message, the identical value `ago-console`'s own `DoneStep` renders. */
@Composable
private fun setupWizardStepBody(
    step: SetupWizardStep,
    calendarName: String?,
    triggerWord: String,
): String =
    when (step) {
        SetupWizardStep.CreateCalendar -> stringResource(R.string.setup_wizard_create_calendar_intro)
        SetupWizardStep.AddMaster -> stringResource(R.string.setup_wizard_add_master_intro)
        SetupWizardStep.AddService -> stringResource(R.string.setup_wizard_add_service_intro)
        SetupWizardStep.WorkingHours -> stringResource(R.string.setup_wizard_working_hours_intro)
        SetupWizardStep.ConfirmSchedule -> stringResource(R.string.setup_wizard_confirm_schedule_intro)
        SetupWizardStep.Materializing -> stringResource(R.string.setup_wizard_materializing_intro)
        SetupWizardStep.BookingTrigger -> stringResource(R.string.setup_wizard_booking_trigger_intro)
        SetupWizardStep.Publish ->
            calendarName?.let { stringResource(R.string.setup_wizard_publish_intro_named, it) }
                ?: stringResource(R.string.setup_wizard_publish_intro)
        SetupWizardStep.Done -> stringResource(R.string.setup_wizard_done_message, triggerWord)
    }
