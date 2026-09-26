package ago.chat.android.core.network.widgetconfig

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherIconSize
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherPlacement
import ago.chat.android.core.domain.widgetconfig.WidgetAutoOpenDelay
import ago.chat.android.core.domain.widgetconfig.WidgetConfig
import ago.chat.android.core.domain.widgetconfig.WidgetConfigApi
import ago.chat.android.core.domain.widgetconfig.WidgetConfigResult
import ago.chat.android.core.domain.widgetconfig.WidgetConfigWriteResult
import ago.chat.android.core.domain.widgetconfig.WidgetLocale
import ago.chat.android.core.domain.widgetconfig.WidgetPosition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-193` (`docs/design/tenant-widget-android.md` §4.2): the adapter behind [WidgetConfigApi] — the
 * same "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.tags.KtorConversationTagsApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for both [fetch] and [update] — the
 * identical reason [ago.chat.android.core.network.tags.KtorConversationTagsApi]'s own doc comment gives
 * for its own `{siteId}`-scoped read: `{siteId}` is in the URL itself here, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer token
 * is attached by that same client plugin, never threaded through either method here.
 *
 * **[update] serialises all 16 [WidgetConfig] fields on every call.** There is no partial-update overload
 * on [WidgetConfigApi], and [WidgetConfigWireDto] declares no defaults for exactly the reason given on
 * its own doc comment, so there is no field the mapping below can skip — the structural half of the
 * absent-boolean defence `docs/design/tenant-widget-android.md` §3 describes (the other half, "never
 * build a save from anything but the committed whole config", is the shared view model's job, one layer
 * up in `:app`).
 */
public class KtorWidgetConfigApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : WidgetConfigApi {
    /** `GET /api/v1/sites/{siteId}/widget-config`. */
    override suspend fun fetch(): WidgetConfigResult {
        val siteId = activeSite.currentSiteId() ?: return WidgetConfigResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/widget-config")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WidgetConfigResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say - the
            // identical reasoning `KtorInstallationApi`'s own doc comment records for its own sibling read.
            return WidgetConfigResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            WidgetConfigResult.Loaded(response.body<WidgetConfigWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "the widget's own built-in defaults" -
            // the identical `KtorConversationTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            WidgetConfigResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `PUT /api/v1/sites/{siteId}/widget-config`, body = the complete [config]. */
    override suspend fun update(config: WidgetConfig): WidgetConfigWriteResult {
        val siteId = activeSite.currentSiteId() ?: return WidgetConfigWriteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.put("$apiBaseUrl/api/v1/sites/$siteId/widget-config") {
                    contentType(ContentType.Application.Json)
                    setBody(config.toWireDto())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WidgetConfigWriteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                WidgetConfigWriteResult.Saved(response.body<WidgetConfigWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                WidgetConfigWriteResult.Failed(NetworkFailure.from(failure))
            }
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { WidgetConfigWriteResult.Refused(it) }
            ?: WidgetConfigWriteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this package keeps for itself
 * ([ago.chat.android.core.network.team.KtorOperatorTeamApi]'s own doc comment states why). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/**
 * `Ago.Chat.Api.WidgetConfig.WidgetConfigEndpoints`' `UpdateWidgetConfigRequest`/`WidgetConfigResponse`
 * shape, field for field (`docs/design/tenant-widget-android.md` §1.1/§4.2) — one private DTO for both
 * the `PUT` body and every `2xx` echo (`GET` and `PUT`), since the two are the identical shape on the
 * wire, the same "one DTO, both directions" shape [ago.chat.android.core.network.contactdetails.KtorContactDetailsApi]'s
 * own `ContactDetailWireDto` already establishes.
 *
 * **Deliberately no default value on any field**, unlike a decode-only response DTO (e.g. that same file's
 * `ContactDetailWireDto.assessment`, defaulted for a legacy row that predates the field). This DTO also
 * builds the outgoing `PUT` body, and every other outgoing wire DTO in this app —
 * `SaveWorkerScheduleWireDto`, `CreateWorkerRequestWireDto`, `UpdateWorkingHoursRuleWireDto` — declares no
 * defaults for the identical reason `KtorWorkerScheduleApi`'s own doc comment states: a default risks
 * `agoJson` omitting an at-default field from the outgoing JSON if this app's serializer config ever turns
 * `encodeDefaults` off, which for `requireContactConsent` — `[JsonRequired]` server-side precisely so a
 * missing value is a hard failure rather than a silently disabled consent gate — would turn a routine
 * serializer change into a live risk to that gate. Declaring no defaults here means every field is a
 * required constructor parameter, so `kotlinx.serialization` always encodes all 16 of them regardless of
 * value or of any future `agoJson` change — the field-level half of the absent-boolean defence this
 * class's own doc comment describes. Safe on the decode side too:
 * `WidgetConfigResponse` always sends all 16 fields (`docs/design/tenant-widget-android.md` §1), so there
 * is no legacy-row case here needing a resilience default the way that DTO's own `assessment` field has.
 */
@Serializable
private data class WidgetConfigWireDto(
    val primaryColorHex: String?,
    val position: String,
    val locale: String,
    val noticeText: String?,
    val noticeUrl: String?,
    val requireContactConsent: Boolean,
    val attractAttention: Boolean,
    val autoOpenEnabled: Boolean,
    val autoOpenDelaySeconds: Int,
    val autoOpenGreetingText: String?,
    val acceptUnverifiedPhone: Boolean,
    val allowAttachmentUploadsByDefault: Boolean,
    val contactCaptureConfirmationText: String?,
    val channelSwitcherPlacement: String,
    val channelSwitcherIconSize: String,
    val panelTitle: String?,
)

private fun WidgetConfigWireDto.toDomain() =
    WidgetConfig(
        primaryColorHex = primaryColorHex,
        position = position.toWidgetPosition(),
        locale = locale.toWidgetLocale(),
        panelTitle = panelTitle,
        attractAttention = attractAttention,
        autoOpenEnabled = autoOpenEnabled,
        autoOpenDelay = WidgetAutoOpenDelay.fromSeconds(autoOpenDelaySeconds),
        autoOpenGreetingText = autoOpenGreetingText,
        channelSwitcherPlacement = channelSwitcherPlacement.toChannelSwitcherPlacement(),
        channelSwitcherIconSize = channelSwitcherIconSize.toChannelSwitcherIconSize(),
        noticeText = noticeText,
        noticeUrl = noticeUrl,
        requireContactConsent = requireContactConsent,
        contactCaptureConfirmationText = contactCaptureConfirmationText,
        acceptUnverifiedPhone = acceptUnverifiedPhone,
        allowAttachmentUploadsByDefault = allowAttachmentUploadsByDefault,
    )

private fun WidgetConfig.toWireDto() =
    WidgetConfigWireDto(
        primaryColorHex = primaryColorHex,
        position = position.name,
        locale = locale.name,
        noticeText = noticeText,
        noticeUrl = noticeUrl,
        requireContactConsent = requireContactConsent,
        attractAttention = attractAttention,
        autoOpenEnabled = autoOpenEnabled,
        autoOpenDelaySeconds = autoOpenDelay.seconds,
        autoOpenGreetingText = autoOpenGreetingText,
        acceptUnverifiedPhone = acceptUnverifiedPhone,
        allowAttachmentUploadsByDefault = allowAttachmentUploadsByDefault,
        contactCaptureConfirmationText = contactCaptureConfirmationText,
        channelSwitcherPlacement = channelSwitcherPlacement.name,
        channelSwitcherIconSize = channelSwitcherIconSize.name,
        panelTitle = panelTitle,
    )

/** An unrecognised wire spelling falls back to the server's own documented default
 * (`docs/design/tenant-widget-android.md` §1.1) rather than failing decode - the same "old client, new
 * server value, keep working" tolerance [WidgetAutoOpenDelay.fromSeconds] already extends to its own
 * unrecognised int. */
private fun String.toWidgetPosition(): WidgetPosition =
    when (this) {
        "BottomLeft" -> WidgetPosition.BottomLeft
        else -> WidgetPosition.BottomRight
    }

private fun String.toWidgetLocale(): WidgetLocale =
    when (this) {
        "Ru" -> WidgetLocale.Ru
        else -> WidgetLocale.En
    }

private fun String.toChannelSwitcherPlacement(): ChannelSwitcherPlacement =
    when (this) {
        "BelowLauncher" -> ChannelSwitcherPlacement.BelowLauncher
        else -> ChannelSwitcherPlacement.AboveComposer
    }

private fun String.toChannelSwitcherIconSize(): ChannelSwitcherIconSize =
    when (this) {
        "Large" -> ChannelSwitcherIconSize.Large
        "Small" -> ChannelSwitcherIconSize.Small
        else -> ChannelSwitcherIconSize.Medium
    }
