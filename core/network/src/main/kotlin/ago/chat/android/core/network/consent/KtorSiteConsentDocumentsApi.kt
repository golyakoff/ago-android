package ago.chat.android.core.network.consent

import ago.chat.android.core.domain.consent.ConsentAcceptance
import ago.chat.android.core.domain.consent.ConsentAcceptancesResult
import ago.chat.android.core.domain.consent.ConsentDocumentSummary
import ago.chat.android.core.domain.consent.ConsentOverview
import ago.chat.android.core.domain.consent.ConsentPublishResult
import ago.chat.android.core.domain.consent.ConsentPurpose
import ago.chat.android.core.domain.consent.ConsentVersion
import ago.chat.android.core.domain.consent.SiteConsentDocumentsApi
import ago.chat.android.core.domain.consent.SiteConsentDocumentsResult
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §2.3): the adapter behind
 * [SiteConsentDocumentsApi] — the same "the whole status-code-to-meaning mapping lives here, and only
 * here" shape [ago.chat.android.core.network.cannedresponses.KtorCannedResponsesApi] already
 * establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for every method — the identical reason
 * [KtorCannedResponsesApi][ago.chat.android.core.network.cannedresponses.KtorCannedResponsesApi]'s own
 * doc comment gives for its own `{siteId}`-scoped routes: `{siteId}` is in the URL itself, not only in
 * the `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer
 * token is attached by that same client plugin, never threaded through any method here.
 *
 * **A parse failure on an otherwise-2xx body is [NetworkFailure.Unexpected], never an empty read** — the
 * identical `KtorConversationTagsApi`/`shapeGuard.ts` lesson every adapter in this app already follows.
 * Timestamps are parsed with [OffsetDateTime.parse] and never wrapped in a per-field `runCatching`: a
 * malformed one throws, and the surrounding `try`/`catch` around the whole body-to-domain mapping is
 * what turns that into [NetworkFailure.Unexpected] rather than a fabricated `null`.
 */
public class KtorSiteConsentDocumentsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : SiteConsentDocumentsApi {
    /** `GET /api/v1/sites/{siteId}/consent-documents`. */
    override suspend fun fetchOverview(): SiteConsentDocumentsResult {
        val siteId = activeSite.currentSiteId() ?: return SiteConsentDocumentsResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(consentDocumentsUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SiteConsentDocumentsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say -
            // the identical reasoning `KtorCannedResponsesApi`'s own doc comment records for its own
            // sibling read.
            return SiteConsentDocumentsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            SiteConsentDocumentsResult.Loaded(response.body<OverviewWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            SiteConsentDocumentsResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `GET /api/v1/sites/{siteId}/consent-documents/{purpose}/acceptances` — a raw JSON array, no
     * wrapper object. */
    override suspend fun fetchAcceptances(purpose: ConsentPurpose): ConsentAcceptancesResult {
        val siteId = activeSite.currentSiteId() ?: return ConsentAcceptancesResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("${consentDocumentsUrl(siteId)}/${purpose.slug}/acceptances")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConsentAcceptancesResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return ConsentAcceptancesResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            ConsentAcceptancesResult.Loaded(response.body<List<AcceptanceWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ConsentAcceptancesResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/consent-documents/{purpose}`, body `{title, body}`. `409` is
     * [ConsentPublishResult.Conflict]; any other non-2xx is read for an RFC 7807 `detail` the identical
     * way [ago.chat.android.core.network.tags.KtorConversationTagsApi.performTagAction]'s own doc
     * comment describes. */
    override suspend fun publish(
        purpose: ConsentPurpose,
        title: String,
        body: String,
    ): ConsentPublishResult {
        val siteId = activeSite.currentSiteId() ?: return ConsentPublishResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("${consentDocumentsUrl(siteId)}/${purpose.slug}") {
                    contentType(ContentType.Application.Json)
                    setBody(PublishRequestWireDto(title = title, body = body))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConsentPublishResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                ConsentPublishResult.Published(response.body<PublishResponseWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                ConsentPublishResult.Failed(NetworkFailure.from(failure))
            }
        }

        if (response.status == HttpStatusCode.Conflict) {
            return ConsentPublishResult.Conflict
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { ConsentPublishResult.Refused(it) }
            ?: ConsentPublishResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun consentDocumentsUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/consent-documents"
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared
 * copy every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `SiteConsentDocumentEndpoints.PublishedVersionResponse`, field for field — no body. */
@Serializable
private data class VersionWireDto(
    val version: String,
    val sequence: Int,
    val title: String,
    val publishedAt: String,
)

/** `SiteConsentDocumentEndpoints.SiteConsentDocumentResponse`. [purpose] is echoed by the server but
 * never read here — the caller already knows which purpose it asked for from the enclosing field
 * ([OverviewWireDto.contact]/[OverviewWireDto.marketing]), the identical "never parse back what you
 * already know" discipline [ago.chat.android.core.network.installation.KtorInstallationApi]'s own doc
 * comment states for `documentKey`. */
@Serializable
private data class DocumentWireDto(
    val purpose: String,
    val documentKey: String,
    val versions: List<VersionWireDto> = emptyList(),
)

/** `SiteConsentDocumentEndpoints.SiteConsentDocumentsResponse`. */
@Serializable
private data class OverviewWireDto(
    val contact: DocumentWireDto,
    val contactConsentRequired: Boolean = false,
    val marketing: DocumentWireDto,
)

/** The `POST` request body — no `id`, the route itself already names `{siteId}` and `{purpose}`. */
@Serializable
private data class PublishRequestWireDto(
    val title: String,
    val body: String,
)

/** `SiteConsentDocumentEndpoints.PublishedSiteConsentDocumentResponse` — uniquely carries [body] among
 * this adapter's read shapes, and it is discarded: the overview reload the view model runs after a
 * successful publish is the source of truth, never this echo. */
@Serializable
private data class PublishResponseWireDto(
    val documentKey: String,
    val version: String,
    val sequence: Int,
    val title: String,
    val body: String = "",
    val publishedAt: String,
)

/** `SiteConsentDocumentEndpoints.SiteConsentAcceptanceResponse` — no `clientIp`/`userAgent`; those
 * never reach the wire at all (`adr/0146`). */
@Serializable
private data class AcceptanceWireDto(
    val subjectKind: String,
    val subjectId: String,
    val documentVersion: String,
    val acceptedAt: String,
)

private fun String.toInstant(): Instant = OffsetDateTime.parse(this).toInstant()

private fun VersionWireDto.toDomain() =
    ConsentVersion(version = version, sequence = sequence, title = title, publishedAt = publishedAt.toInstant())

private fun DocumentWireDto.toDomain(purpose: ConsentPurpose) =
    ConsentDocumentSummary(purpose = purpose, documentKey = documentKey, versions = versions.map { it.toDomain() })

private fun OverviewWireDto.toDomain() =
    ConsentOverview(
        contact = contact.toDomain(ConsentPurpose.Contact),
        contactConsentRequired = contactConsentRequired,
        marketing = marketing.toDomain(ConsentPurpose.Marketing),
    )

private fun PublishResponseWireDto.toDomain() =
    ConsentVersion(version = version, sequence = sequence, title = title, publishedAt = publishedAt.toInstant())

private fun AcceptanceWireDto.toDomain() =
    ConsentAcceptance(
        subjectKind = subjectKind,
        subjectId = subjectId,
        documentVersion = documentVersion,
        acceptedAt = acceptedAt.toInstant(),
    )
