package ago.chat.android.shell

import ago.chat.android.devices.AutostartAdvisor
import ago.chat.android.devices.AutostartSettingsTarget
import ago.chat.android.devices.BatteryAwarenessPromptPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `26-128`: the first-launch bottom sheet's own state — whether to show it at all, and which
 * autostart screen its own button should open. Battery mode needs no state here: the sheet's copy is
 * fixed regardless of the current system value (it replaces the raw *first-launch* system prompt, not a
 * live status reading — that live read belongs to [SettingsViewModel]'s own two rows instead), so this
 * class asks [BatteryAwarenessPromptPreferences] a single yes/no question and nothing else.
 *
 * **Read once, at construction, never refreshed.** Unlike [SettingsViewModel.notificationsEnabled], which
 * re-reads on every `ON_RESUME` because the operator can act on it and come straight back, this sheet is
 * shown at most once per app lifetime (until dismissed) and has no reason to reappear mid-session just
 * because some other screen recomposed.
 */
@HiltViewModel
public class BatteryAwarenessViewModel
    @Inject
    constructor(
        private val promptPreferences: BatteryAwarenessPromptPreferences,
        private val autostartAdvisor: AutostartAdvisor,
    ) : ViewModel() {
        private val mutableVisible = MutableStateFlow(false)

        /** `false` until the persisted flag has actually been read — a fresh install must never flash
         * the sheet on for one frame while `init` is still loading, since [dismiss] with the checkbox
         * unchecked is a real, valid, non-blocking outcome this class must not race. */
        public val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()

        /** [ago.chat.android.shell.SettingsRoute]'s own «Автозапуск» row reads the identical port
         * directly; this sheet reads it once here rather than through a second collected `Flow`, since
         * the value cannot change for the life of this `ViewModel` — [Build.MANUFACTURER][android.os.Build.MANUFACTURER]
         * is fixed for the life of the process. */
        public val autostartTarget: AutostartSettingsTarget = autostartAdvisor.settingsTarget()

        init {
            viewModelScope.launch {
                mutableVisible.value = !promptPreferences.dismissed.first()
            }
        }

        /**
         * `26-128`'s own non-blocking contract: closing the sheet — with or without [dontShowAgain]
         * checked — only ever hides it for *this* composition, never gates anything the app does. The
         * persisted flag is written only when [dontShowAgain] is `true`; leaving it unchecked means the
         * sheet is offered again next time the signed-in shell composes from scratch (a fresh process,
         * not merely a recomposition — [visible] itself is not reset here).
         */
        public fun dismiss(dontShowAgain: Boolean) {
            mutableVisible.value = false
            if (dontShowAgain) {
                viewModelScope.launch { promptPreferences.setDismissed(true) }
            }
        }
    }
