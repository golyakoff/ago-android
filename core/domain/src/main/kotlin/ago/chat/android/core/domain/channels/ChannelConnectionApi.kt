package ago.chat.android.core.domain.channels

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-188`: the port the Каналы → Telegram/MAX/VK screens read and write through — one parameterised
 * port rather than three near-identical copies, because on the wire the three channels are **one
 * contract read three times**: the status response is byte-identical across all three
 * (`TelegramChannelStatusResponse` = `MaxChannelStatusResponse` = `VkChannelStatusResponse`), and the
 * connect response differs only in VK's two extra plaintext fields. Declared here, in `:core:domain`,
 * and implemented in `:core:network` (`KtorChannelConnectionApi`) — the identical port/adapter split
 * [ago.chat.android.core.domain.installation.InstallationApi] and
 * [ago.chat.android.core.domain.tags.ConversationTagsApi] already establish. The dependency rule is
 * what puts it here rather than beside the Ktor client: a view model holding an `HttpClient` directly
 * could not be tested without one, and every HTTP-shaped decision (which status means what, which base
 * URL, which `{siteId}`) belongs on the far side of this interface, in the adapter.
 *
 * **One port per [ChannelKind], not three ports.** The backend keeps three separate response records
 * "rather than extracted into a shared shape" because each provider's own handler is genuinely
 * different provider-shaped work; the **client** consumes a uniform shape, and three copy-pasted
 * adapters would be exactly the triplication a shared-shape client is meant to avoid. The one real
 * divergence — VK's shown-once `callbackUrl`/`webhookSecret` reveal — is modelled as nullable fields on
 * [ChannelConnectResult], not a second port.
 */
public interface ChannelConnectionApi {
    /** `GET /api/v1/sites/{siteId}/channels/{kind.slug}`, `site:configure`-gated on the client (the row
     * that opens this screen), `channel:manage`-enforced on the server. */
    public suspend fun fetchStatus(kind: ChannelKind): ChannelStatusResult

    /** `POST /api/v1/sites/{siteId}/channels/{kind.slug}` with `{"token": token}`. The token never
     * round-trips (`adr/0069`): no response this port's caller ever reads carries it back. */
    public suspend fun connect(
        kind: ChannelKind,
        token: String,
    ): ChannelConnectResult

    /** `DELETE /api/v1/sites/{siteId}/channels/{kind.slug}/{channelCredentialId}`. */
    public suspend fun disconnect(
        kind: ChannelKind,
        channelCredentialId: String,
    ): ChannelDisconnectResult
}

/** `26-188`: the three token channels this scaffold serves — `.slug` is the one URL segment
 * `KtorChannelConnectionApi` needs to build any of its three routes. MAX and VK are declared now, ahead
 * of their own slices (`C2`/`C3`), because [ChannelKind] is the one thing every future channel screen
 * threads through the same port and view model base — adding a channel later is adding an enum entry
 * and a thin subclass, never a new port. */
public enum class ChannelKind(
    public val slug: String,
) {
    Telegram("telegram"),
    Max("max"),
    Vk("vk"),
}

/**
 * `26-188`: the live status read. [verified] is `null` whenever [connected] is `false` or [unreachable]
 * is `true` — there is nothing to have verified yet, or the provider could not be reached to ask.
 * [refusalReason] is present only when the provider was reachable and refused
 * (`verified == false && !unreachable`); a connected-and-reachable-but-not-yet-checked state does not
 * exist on the wire, so this type carries no arm for it.
 */
public data class ChannelStatus(
    val connected: Boolean,
    val channelCredentialId: String?,
    val createdAt: Instant?,
    val verified: Boolean?,
    val unreachable: Boolean,
    val refusalReason: String?,
    val checkedAt: Instant,
)

/** What reading a channel's status came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.tags.TagVocabularyResult] already establishes. Deliberately **no**
 * `NotConnected` arm: `connected == false` is a *loaded* [ChannelStatus], not a load failure. */
public sealed interface ChannelStatusResult {
    public data class Loaded(
        val status: ChannelStatus,
    ) : ChannelStatusResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ChannelStatusResult
}

/** `26-188`/`C3`: VK's own shown-once connect payload — a secret **AGO generated for the shop**, needed
 * by a human pasting it into VK's own community Callback API settings, not the shop's own bot token
 * (`adr/0069`'s one named exception). Carried by [ChannelConnectResult.Connected] alone; never by
 * [ChannelStatusResult], since no status read ever carries it again. */
public data class VkReveal(
    val callbackUrl: String,
    val webhookSecret: String,
)

/** What a connect attempt came back with. [Connected.reveal] is non-null only for a VK connect — every
 * other channel's [connect] always returns `reveal = null`. [Refused.detail] is the server's own
 * problem-details `detail`, shown verbatim (a bad token, already-connected, or VK-not-available on this
 * deployment) — the identical "show what the server said" shape
 * [ago.chat.android.core.domain.tags.TagActionResult.Refused] already establishes. */
public sealed interface ChannelConnectResult {
    public data class Connected(
        val reveal: VkReveal?,
    ) : ChannelConnectResult

    public data class Refused(
        val detail: String,
    ) : ChannelConnectResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ChannelConnectResult
}

/** What a disconnect attempt came back with — the identical three-arm shape [ChannelConnectResult]
 * establishes, restated rather than shared since a disconnect carries no reveal to ever be non-null. */
public sealed interface ChannelDisconnectResult {
    public data object Disconnected : ChannelDisconnectResult

    public data class Refused(
        val detail: String,
    ) : ChannelDisconnectResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ChannelDisconnectResult
}
