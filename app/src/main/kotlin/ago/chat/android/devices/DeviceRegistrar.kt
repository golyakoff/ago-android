package ago.chat.android.devices

import kotlinx.coroutines.flow.StateFlow

/**
 * `26-06`: the narrow view [ago.chat.android.signin.SignInViewModel] needs onto the registration half
 * of [DeviceRegistrationCoordinator] - deliberately excludes [DeviceRegistrationScheduler]'s own
 * `schedulePeriodicRegistration`, which is a *separate* interface for a reason stated on that one:
 * `SignInViewModel` needs both, but they are backed by two different classes (one Context-free, one
 * not), and a caller depending on two narrow interfaces rather than one wide one is what
 * `SignInViewModelTest`'s own two independent fakes can stay this simple.
 */
public interface DeviceRegistrar {
    /** Relayed from [DeviceRegistrationCoordinator.pushAvailability] - see that property's own doc
     * comment. */
    public val pushAvailability: StateFlow<PushAvailability?>

    public suspend fun registerThisDevice(): Boolean
}
