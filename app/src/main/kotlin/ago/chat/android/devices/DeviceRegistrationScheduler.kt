package ago.chat.android.devices

/**
 * `26-06`: the third of the three call sites `docs/architecture/push-notifications.md` names -
 * ensuring [DeviceRegistrationWorker] is enqueued. A separate interface from [DeviceRegistrar] rather
 * than one method added to it, because the two are backed by genuinely different classes:
 * [DeviceRegistrationCoordinator] (this one's own sibling) holds no `Context` and is plain-JVM-testable
 * on purpose, while scheduling *requires* `WorkManager.getInstance(context)` - folding the two into one
 * interface would force every [DeviceRegistrar] fake to also stub a method it has no Android runtime
 * to honestly implement. [ago.chat.android.signin.SignInViewModel] simply depends on both.
 */
public interface DeviceRegistrationScheduler {
    public fun schedulePeriodicRegistration()
}
