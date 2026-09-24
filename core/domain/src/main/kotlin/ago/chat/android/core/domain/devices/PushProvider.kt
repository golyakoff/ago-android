package ago.chat.android.core.domain.devices

/**
 * `26-100`/`adr/0181`: which push transport this device registered through - the client-side twin of
 * `Ago.Chat.Domain.PushProvider` on the server (`ago-chat/src/Ago.Chat.Domain/PushProvider.cs`). Lives
 * in `:core:domain`, not `:app`, because [DeviceRegistrationApi] - the port this value travels through -
 * already lives here: a REST call's own wire shape generalises to any client shape a future KMP
 * `commonMain` might reuse (`adr/0178`), the identical reasoning that interface's own doc comment gives.
 *
 * [wireValue] is what actually crosses the wire in `RegisterDeviceRequestWireDto.provider` - a real
 * field read by name, never `.name`/`.toString()`, so a future reorder or rename of this enum's members
 * cannot silently change what the server reads without a compile error pointing at this file.
 */
public enum class PushProvider(
    public val wireValue: String,
) {
    Fcm("fcm"),
    RuStore("rustore"),
}
