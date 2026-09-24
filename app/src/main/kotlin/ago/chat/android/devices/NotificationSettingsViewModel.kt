package ago.chat.android.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `26-19`: the notification settings screen's own state — two genuinely separate concerns sharing one
 * view model because they share one screen, the identical reasoning [ago.chat.android.shell
 * .SettingsViewModel]'s own doc comment states for its three: [channelStates] (a live read of Android's
 * own truth, refreshed on demand) and [quietHours] (this app's own persisted preference, read and
 * written through [QuietHoursPreferences]).
 *
 * **`internal`, not `public`.** [channelStates]' own type mentions [PushNotificationChannel], which is
 * itself `internal` — the Kotlin compiler's own `EXPOSED_PROPERTY_TYPE` rule forbids a `public` member
 * from exposing a less-visible type in its signature, regardless of both living in the same `:app`
 * module. Narrowing this class (and [NotificationSettingsRoute], which takes it as a default parameter)
 * to `internal` is the fix that keeps [PushNotificationChannel] itself `internal` — the alternative,
 * widening that enum to `public`, would advertise it as a cross-module API when nothing outside `:app`
 * has ever needed to see it.
 */
@HiltViewModel
internal class NotificationSettingsViewModel
    @Inject
    constructor(
        private val channelStateReader: NotificationChannelStateReader,
        private val quietHoursPreferences: QuietHoursPreferences,
    ) : ViewModel() {
        private val mutableChannelStates = MutableStateFlow(readChannelStates())

        /** One entry per [PushNotificationChannel] - a closed set matching the fan-out's own kinds
         * one-for-one ([PushNotificationChannel]'s own doc comment states why an unmatched channel is
         * never declared). `true` means Android currently shows this kind of push; `false` means the
         * operator (or a device policy) turned that channel's importance down to
         * [android.app.NotificationManager.IMPORTANCE_NONE] from system Settings. */
        val channelStates: StateFlow<Map<PushNotificationChannel, Boolean>> = mutableChannelStates.asStateFlow()

        val quietHours: StateFlow<QuietHoursSettings> =
            quietHoursPreferences.settings.stateIn(viewModelScope, SharingStarted.Eagerly, QuietHoursSettings())

        /** [ago.chat.android.shell.SettingsViewModel.refreshNotificationPermission]'s own `ON_RESUME`
         * shape, applied to channel importance instead of the app-wide permission: an operator who left
         * for the system per-channel settings screen and came back must see the real, current importance,
         * not the value this class happened to read when the screen first composed. */
        fun refreshChannelStates() {
            mutableChannelStates.value = readChannelStates()
        }

        fun setQuietHoursEnabled(enabled: Boolean) {
            viewModelScope.launch { quietHoursPreferences.setSettings(quietHours.value.copy(enabled = enabled)) }
        }

        fun setQuietHoursRange(
            startMinuteOfDay: Int,
            endMinuteOfDay: Int,
        ) {
            viewModelScope.launch {
                quietHoursPreferences.setSettings(
                    quietHours.value.copy(startMinuteOfDay = startMinuteOfDay, endMinuteOfDay = endMinuteOfDay),
                )
            }
        }

        private fun readChannelStates(): Map<PushNotificationChannel, Boolean> =
            PushNotificationChannel.entries.associateWith { channel ->
                channelImportanceIsOn(channelStateReader.importanceOf(channel.id))
            }
    }
