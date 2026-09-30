package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.modules.ModulesApi
import ago.chat.android.core.domain.modules.ModulesResult
import ago.chat.android.core.domain.readiness.BookingReadinessApi
import ago.chat.android.core.domain.readiness.BookingReadinessResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-332` (`26-318` port): the guided setup wizard's own state machine — one read over
 * [BookingReadinessApi] (the identical read [ReadinessViewModel] makes) plus one supplementary read over
 * [ModulesApi] for the booking-trigger fact readiness does not carry, folded by [deriveSetupWizardStep]
 * into the single step the tenant is on. No write of its own: every step's action navigates the tenant to
 * the existing config screen that owns that write ([SetupWizardStep.targetTab]), so the wizard adds no
 * second copy of any write logic (`26-332` brief: "reuse existing flows, do not duplicate write logic").
 *
 * Constructed only for an operator holding `calendar:configure` ([BookingsRoute] calls `hiltViewModel()`
 * only inside that branch), the identical gate every other Записи config screen checks. [refresh] is the one
 * method beyond construction — [init] calls it once, [BookingsRoute]'s own reopen `LaunchedEffect` calls it
 * on every open of the screen, and [SetupWizardBody]'s retry calls it a third time, all three the identical
 * request (the same "re-read on open, no local progress store" discipline [ReadinessViewModel] follows).
 */
@HiltViewModel
internal class SetupWizardViewModel
    @Inject
    constructor(
        private val readinessApi: BookingReadinessApi,
        private val modulesApi: ModulesApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<SetupWizardUiState>(SetupWizardUiState.Loading)
        val state: StateFlow<SetupWizardUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            mutableState.update { SetupWizardUiState.Loading }
            viewModelScope.launch {
                val readiness = withContext(ioDispatcher) { readinessApi.fetchReadiness() }
                // The trigger-word read is supplementary — a failed read must never be mistaken for "a
                // trigger word is set" (`26-329` decision 6: the safe default is the closed gate, the same
                // as "no trigger word"), so it degrades quietly to an empty list rather than turning the
                // whole screen into a failure the way a failed readiness read does.
                val triggerWords =
                    when (val modules = withContext(ioDispatcher) { modulesApi.fetch() }) {
                        is ModulesResult.Loaded -> {
                            val bookingModule = modules.modules.firstOrNull { it.moduleKey == BOOKINGS_MODULE_KEY }
                            bookingModule?.triggerWords.orEmpty()
                        }
                        is ModulesResult.Failed -> emptyList()
                    }

                mutableState.update {
                    when (readiness) {
                        is BookingReadinessResult.Loaded ->
                            loadedState(calendars = readiness.calendars, triggerWords = triggerWords)
                        BookingReadinessResult.NotConfigured -> SetupWizardUiState.NotConfigured
                        is BookingReadinessResult.Failed -> SetupWizardUiState.Failed(readiness.reason)
                    }
                }
            }
        }

        private fun loadedState(
            calendars: List<ago.chat.android.core.domain.readiness.CalendarReadiness>,
            triggerWords: List<String>,
        ): SetupWizardUiState {
            // An empty readiness list is a response shape this screen cannot place anyone in — the handler
            // always returns at least the synthetic no-calendar placeholder, so this is defensive
            // completeness, not a state reached in practice.
            val step =
                deriveSetupWizardStep(calendars, hasBookingTrigger = triggerWords.isNotEmpty())
                    ?: return SetupWizardUiState.Failed(BookingsQueueFailure.Unexpected)
            return SetupWizardUiState.Loaded(
                step = step,
                calendarName = calendars.first().calendarName,
                triggerWord = triggerWords.firstOrNull() ?: DEFAULT_TRIGGER_WORD,
            )
        }

        private companion object {
            /** `ago-console`'s own `BOOKINGS_MODULE_KEY` — the calendar/booking module a trigger word is set
             * against, distinct from `"faq"` and every other module the same list carries. */
            const val BOOKINGS_MODULE_KEY = "calendar"

            /** `ago-console`'s own `DEFAULT_TRIGGER_WORD` — shown in the «Готово» message when no word is set
             * yet, so the done state always names a concrete phrase rather than an empty placeholder. */
            const val DEFAULT_TRIGGER_WORD = "/записаться"
        }
    }
