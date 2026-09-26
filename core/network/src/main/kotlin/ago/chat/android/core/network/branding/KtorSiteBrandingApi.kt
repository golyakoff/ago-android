package ago.chat.android.core.network.branding

import ago.chat.android.core.domain.branding.BrandingWriteResult
import ago.chat.android.core.domain.branding.LogoStatus
import ago.chat.android.core.domain.branding.LogoUploadResult
import ago.chat.android.core.domain.branding.SiteBranding
import ago.chat.android.core.domain.branding.SiteBrandingApi
import ago.chat.android.core.domain.branding.SiteBrandingResult
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.2): the adapter behind [SiteBrandingApi] —
 * the same "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.widgetconfig.KtorWidgetConfigApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for all three methods — the identical
 * reason [ago.chat.android.core.network.channels.KtorChannelConnectionApi]'s own doc comment gives for
 * its own `{siteId}`-scoped routes: `{siteId}` is in the URL itself, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer token
 * is attached by that same client plugin, never threaded through any method here.
 *
 * **[uploadLogo] never touches `ContentResolver`.** It receives a plain [ByteArray] plus the MIME
 * [String] its caller already read from the picked `content://` `Uri` — [SiteBrandingApi]'s own doc
 * comment states why that read is `:app`'s job, never this module's: gaining an Android framework
 * dependency here would put a concrete platform type behind what must stay a plain Ktor adapter,
 * testable (as `KtorSiteBrandingApiTest` does) with nothing but a `MockEngine`.
 */
public class KtorSiteBrandingApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : SiteBrandingApi {
    /** `GET /api/v1/sites/{siteId}/branding`. */
    override suspend fun fetch(): SiteBrandingResult {
        val siteId = activeSite.currentSiteId() ?: return SiteBrandingResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(brandingUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SiteBrandingResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say -
            // the identical reasoning `KtorWidgetConfigApi`'s own doc comment records for its own read.
            return SiteBrandingResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            SiteBrandingResult.Loaded(response.body<SiteBrandingWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no branding set yet" - the
            // identical `KtorConversationTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            SiteBrandingResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `PUT /api/v1/sites/{siteId}/branding`, body `{"brandCompanyName": name}`. */
    override suspend fun updateCompanyName(name: String?): BrandingWriteResult {
        val siteId = activeSite.currentSiteId() ?: return BrandingWriteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.put(brandingUrl(siteId)) {
                    contentType(ContentType.Application.Json)
                    setBody(CompanyNameWireDto(name))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BrandingWriteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                BrandingWriteResult.Saved(response.body<CompanyNameWireDto>().brandCompanyName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                BrandingWriteResult.Failed(NetworkFailure.from(failure))
            }
        }

        return problemDetail(response)?.let { BrandingWriteResult.Refused(it) }
            ?: BrandingWriteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    /** `POST /api/v1/sites/{siteId}/branding/logo`, body = the raw [bytes] (never multipart),
     * `Content-Type` = [contentType]. A `2xx` carries only the freshly-set [LogoStatus] - ordinarily
     * [LogoStatus.Pending] - never the logo's own eventual [SiteBranding.logoUrl]. */
    override suspend fun uploadLogo(
        bytes: ByteArray,
        contentType: String,
    ): LogoUploadResult {
        val siteId = activeSite.currentSiteId() ?: return LogoUploadResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("${brandingUrl(siteId)}/logo") {
                    contentType(ContentType.parse(contentType))
                    setBody(bytes)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return LogoUploadResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                LogoUploadResult.Accepted(response.body<LogoUploadResponseWireDto>().logoStatus.toLogoStatus())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                LogoUploadResult.Failed(NetworkFailure.from(failure))
            }
        }

        // A rate-limited upload (5/day, no Retry-After) is just another problem-details refusal - the
        // server's own words, shown verbatim (`SiteBrandingApi.uploadLogo`'s own doc comment).
        return problemDetail(response)?.let { LogoUploadResult.Refused(it) }
            ?: LogoUploadResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun brandingUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/branding"

    /** A non-2xx response read for an RFC 7807 `detail` — the identical shape
     * [ago.chat.android.core.network.channels.KtorChannelConnectionApi.problemDetail]'s own doc comment
     * describes, restated here rather than shared since every adapter in this codebase keeps its own
     * private copy of this one-field read. */
    private suspend fun problemDetail(response: HttpResponse): String? =
        try {
            response.body<ProblemDetailsWireDto>().detail
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared
 * copy every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** The `GET` response — `logoStatus` always present and defaulted to `"None"` only as a decode-time
 * tolerance for an unrecognised future value, never observed from a well-behaved server (the same "old
 * client, new server value, keep working" posture [toLogoStatus] extends below). */
@Serializable
private data class SiteBrandingWireDto(
    val brandCompanyName: String? = null,
    val logoUrl: String? = null,
    val logoStatus: String = "None",
    val logoRejectionReason: String? = null,
)

private fun SiteBrandingWireDto.toDomain(): SiteBranding =
    SiteBranding(
        brandCompanyName = brandCompanyName,
        logoUrl = logoUrl,
        logoStatus = logoStatus.toLogoStatus(),
        logoRejectionReason = logoRejectionReason,
    )

/** One DTO for both the `PUT` request body and its own `2xx` echo — the identical "one DTO, both
 * directions" shape [ago.chat.android.core.network.widgetconfig.KtorWidgetConfigApi]'s own
 * `WidgetConfigWireDto` already establishes, sized to the one field this endpoint actually carries. */
@Serializable
private data class CompanyNameWireDto(
    val brandCompanyName: String?,
)

@Serializable
private data class LogoUploadResponseWireDto(
    val logoStatus: String,
)

/** An unrecognised wire spelling falls back to [LogoStatus.None] rather than failing decode - the same
 * "old client, new server value, keep working" tolerance
 * [ago.chat.android.core.network.widgetconfig.KtorWidgetConfigApi]'s own `toWidgetPosition` already
 * extends to its own unrecognised string. */
private fun String.toLogoStatus(): LogoStatus =
    when (this) {
        "Pending" -> LogoStatus.Pending
        "Ready" -> LogoStatus.Ready
        "Rejected" -> LogoStatus.Rejected
        else -> LogoStatus.None
    }
