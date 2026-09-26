package ago.chat.android.core.domain.branding

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.1/§3.2): the port behind Каналы → Почта —
 * unlike the three token channels ([ago.chat.android.core.domain.channels.ChannelConnectionApi]), this
 * one has **no per-tenant credential to connect**: it is a settings form over the site's own brand
 * identity, `GET`/`PUT /api/v1/sites/{siteId}/branding` plus a third, logo-only
 * `POST /api/v1/sites/{siteId}/branding/logo`. Declared here, implemented in `:core:network`
 * (`KtorSiteBrandingApi`) — the identical port/adapter split
 * [ago.chat.android.core.domain.widgetconfig.WidgetConfigApi] already establishes: a view model holding
 * an `HttpClient` directly could not be tested without one, and every HTTP-shaped decision belongs on
 * the far side of this interface, in the adapter.
 *
 * **Two independent writes, not one full-DTO round trip.** [WidgetConfigApi]'s own `update` always
 * carries the whole config because every field there is a live gate the server refuses to leave
 * unspecified. Neither write here has that shape: a company-name save is accepted synchronously and
 * carries only that one field both ways, while a logo upload's real outcome does not exist yet at the
 * moment this call returns — [ago.chat.android.core.domain.branding.SiteBranding.logoStatus] only
 * reaches [LogoStatus.Ready] or [LogoStatus.Rejected] once `Ago.Chat.Worker`'s own validating consumer
 * finishes (`adr/0177`), asynchronously, after this port's [uploadLogo] has already returned
 * [LogoUploadResult.Accepted]. So this port has three independently-callable operations rather than one
 * combined update, and [fetch] is what a caller re-polls (via an explicit refresh, never a background
 * poll — `docs/design/tenant-channels-android.md` §5.4's own edge case) to observe that transition.
 *
 * **Reading the picked image's bytes and MIME type from a `content://` `Uri` is Android framework
 * work** (`ContentResolver`), so it stays in `:app` (the view model, or a small helper beside it) —
 * this port and its adapter take a plain [ByteArray] and [String] content type and never see a `Uri`,
 * the dependency-rule boundary that keeps `:core:network` a plain Ktor module with no Android
 * `ContentResolver` dependency to gain.
 */
public interface SiteBrandingApi {
    /** `GET /api/v1/sites/{siteId}/branding`. */
    public suspend fun fetch(): SiteBrandingResult

    /** `PUT /api/v1/sites/{siteId}/branding`, body `{"brandCompanyName": name}`. [name] is sent
     * verbatim — trimming/blank-to-null is the caller's own courtesy decision, not this port's. */
    public suspend fun updateCompanyName(name: String?): BrandingWriteResult

    /** `POST /api/v1/sites/{siteId}/branding/logo`, body = the raw [bytes], `Content-Type` =
     * [contentType] (not multipart). A `2xx` here means *accepted for validation*, never "the logo is
     * live" — see this interface's own doc comment on why [fetch] is what later proves that. */
    public suspend fun uploadLogo(
        bytes: ByteArray,
        contentType: String,
    ): LogoUploadResult
}

/**
 * `LogoStatus` on the wire, `None`|`Pending`|`Ready`|`Rejected` — the logo upload's own lifecycle.
 * [SiteBranding.logoUrl] is non-null only once this reaches [Ready]; [SiteBranding.logoRejectionReason]
 * is non-null exactly when this is [Rejected].
 */
public enum class LogoStatus {
    None,
    Pending,
    Ready,
    Rejected,
}

/**
 * `26-191`: the whole `SiteBrandingResponse` shape — a site's brand identity, read as one value.
 * [logoUrl] is a plain, non-expiring public URL, meant to be pointed at directly by an image tag
 * (`AsyncImage`'s own `model`), never a presigned link that expires.
 */
public data class SiteBranding(
    val brandCompanyName: String?,
    val logoUrl: String?,
    val logoStatus: LogoStatus,
    val logoRejectionReason: String?,
)

/** What reading the branding came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.widgetconfig.WidgetConfigResult] already establishes for a
 * site-scoped settings read. */
public sealed interface SiteBrandingResult {
    public data class Loaded(
        val branding: SiteBranding,
    ) : SiteBrandingResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : SiteBrandingResult
}

/** What saving the company name came back with. [Saved.brandCompanyName] is the server's own echo —
 * the view model re-seeds its committed name from *this*, never from what it sent, the identical
 * "reload after a write, never an optimistic flip" discipline
 * [ago.chat.android.core.domain.widgetconfig.WidgetConfigWriteResult.Saved]'s own doc comment states. */
public sealed interface BrandingWriteResult {
    public data class Saved(
        val brandCompanyName: String?,
    ) : BrandingWriteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail`, shown to the operator verbatim;
     * nothing was written. */
    public data class Refused(
        val detail: String,
    ) : BrandingWriteResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : BrandingWriteResult
}

/** What a logo upload attempt came back with. [Accepted.status] is the server's own freshly-read
 * [LogoStatus] — ordinarily [LogoStatus.Pending], since validation runs asynchronously
 * (`Ago.Chat.Worker.SiteLogoValidator`, `adr/0177`) — never a claim that the logo is live; only a later
 * [SiteBrandingApi.fetch] reaching [LogoStatus.Ready] is that claim. A rate-limited upload (5/day) is
 * just another [Refused] — the server's own words, shown verbatim, no client-side `Retry-After`
 * handling to add. */
public sealed interface LogoUploadResult {
    public data class Accepted(
        val status: LogoStatus,
    ) : LogoUploadResult

    public data class Refused(
        val detail: String,
    ) : LogoUploadResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : LogoUploadResult
}
