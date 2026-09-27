package ago.chat.android.core.network.team

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorRoleSeat
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorInviteListItem
import ago.chat.android.core.domain.team.OperatorInviteStatus
import ago.chat.android.core.domain.team.OperatorInvitesResult
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.RevokeInviteResult
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.core.domain.team.SeatSummaryResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-55`: the adapter behind [OperatorTeamApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.conversations.KtorConversationsApi]'s
 * own doc comment states, over `{siteId}`-scoped paths this app has not needed before.
 *
 * [ActiveSiteSelection.currentSiteId] itself is read directly here rather than left implicit — the
 * identical reason `ago.chat.android.core.network.realtime.HubUrl.kt`'s own `buildHubUrl` reads it
 * directly for the operator hub's connection URL: both endpoints carry the site id in the URL itself,
 * not only in a header the server already trusts.
 *
 * `X-Ago-Active-Site` and the bearer token still reach every request through `installAgoRestDefaults`
 * client plugins, unthreaded through either method below — `KtorConversationsApi`'s own remarks explain
 * why that split leaves this class free to be read for "which endpoints does the roster touch" without
 * also being the place tenancy and credentials are handled.
 *
 * **Never a raw exception class name or a hostname on screen** — the same rule
 * [ago.chat.android.core.network.bookings.KtorBookingsApi] already states and already obeys ahead of
 * `26-59` landing app-wide; this adapter is written to obey it from birth for the identical reason that
 * file gives, rather than adding a fifth private `describe(): String` copy for that item to later
 * delete. [classify] answers only "no network" or "something else", by exception type — never by
 * printing `this::class.simpleName` or the exception's own `message`.
 */
public class KtorOperatorTeamApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : OperatorTeamApi {
    /** `GET /api/v1/sites/{siteId}/operators`. */
    override suspend fun fetchTeam(): OperatorTeamResult {
        val siteId = activeSite.currentSiteId() ?: return OperatorTeamResult.Failed(OperatorTeamFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/operators")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return OperatorTeamResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return OperatorTeamResult.Failed(OperatorTeamFailure.Unexpected)
        }

        return try {
            OperatorTeamResult.Loaded(response.body<OperatorTeamResponseWireDto>().operators.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty roster" — the identical
            // `KtorConversationsApi`/`KtorBookingsApi` lesson, read onto this endpoint.
            OperatorTeamResult.Failed(classify(failure))
        }
    }

    /** `GET /api/v1/sites/{siteId}/operators/seat-assignment-summary`. */
    override suspend fun fetchSeatSummary(): SeatSummaryResult {
        val siteId = activeSite.currentSiteId() ?: return SeatSummaryResult.Failed(OperatorTeamFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/operators/seat-assignment-summary")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SeatSummaryResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return SeatSummaryResult.Failed(OperatorTeamFailure.Unexpected)
        }

        return try {
            SeatSummaryResult.Loaded(response.body<SeatAssignmentSummaryWireDto>().roles.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            SeatSummaryResult.Failed(classify(failure))
        }
    }

    /**
     * `POST /api/v1/sites/{siteId}/operator-invites` — `26-56`. [NetworkFailure] rather than
     * [OperatorTeamFailure] for this method's own [CreateInviteResult.Failed] arm: the identical
     * [ago.chat.android.core.network.conversations.KtorConversationsApi.claim] shape for a write that can
     * be genuinely refused with an RFC 7807 `detail`, which [OperatorTeamFailure] (predating `26-59`) has
     * no arm for at all — this is a new call, not a place migrating the two existing reads is this item's
     * job.
     */
    override suspend fun createInvite(
        roleName: String,
        email: String,
    ): CreateInviteResult {
        val siteId = activeSite.currentSiteId() ?: return CreateInviteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/$siteId/operator-invites") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOperatorInviteRequestWireDto(roleName = roleName, email = email))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return CreateInviteResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            val detail =
                try {
                    response.body<ProblemDetailsWireDto>().detail
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
            return detail?.let { CreateInviteResult.Refused(it) }
                ?: CreateInviteResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            response.body<CreateOperatorInviteResponseWireDto>().let {
                CreateInviteResult.Created(
                    operatorInviteId = it.operatorInviteId,
                    code = it.code,
                    expiresAt = it.expiresAt,
                    sendFailed = it.sendFailed,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `2xx` whose body is not the promised shape is not "created with defaults" - the
            // identical `fetchTeam`/`fetchSeatSummary` lesson, read onto a write whose whole point is a
            // one-shot `code` this adapter must never fabricate.
            CreateInviteResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `GET /api/v1/sites/{siteId}/operator-invites` — `26-242`. A read, so it classifies exactly the
     * way [fetchTeam] does: a dropped connection is [OperatorTeamFailure.Transport], any non-2xx or a
     * `200` whose body is not the promised shape (an unrecognised `status` enum member among them) is
     * [OperatorTeamFailure.Unexpected], never a silently-empty invite list. */
    override suspend fun listInvites(): OperatorInvitesResult {
        val siteId = activeSite.currentSiteId() ?: return OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/operator-invites")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return OperatorInvitesResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected)
        }

        return try {
            OperatorInvitesResult.Loaded(response.body<ListOperatorInvitesResponseWireDto>().invites.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            OperatorInvitesResult.Failed(classify(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/operator-invites/{operatorInviteId}/revoke` — `26-242`, `204 No
     * Content` on success. No body to parse either way, so success is just "the status was 2xx"; a
     * dropped connection is [OperatorTeamFailure.Transport] and any non-2xx (the server's own
     * already-redeemed/already-revoked refusal among them) is [OperatorTeamFailure.Unexpected] — the
     * caller re-reads [listInvites] and lets the row's new status speak, never a message parsed here. */
    override suspend fun revokeInvite(operatorInviteId: String): RevokeInviteResult {
        val siteId = activeSite.currentSiteId() ?: return RevokeInviteResult.Failed(OperatorTeamFailure.Unexpected)

        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/$siteId/operator-invites/$operatorInviteId/revoke")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RevokeInviteResult.Failed(classify(failure))
            }

        return if (response.status.isSuccess()) {
            RevokeInviteResult.Revoked
        } else {
            RevokeInviteResult.Failed(OperatorTeamFailure.Unexpected)
        }
    }
}

/** See this file's own class-level doc comment for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): OperatorTeamFailure =
    if (failure is IOException) OperatorTeamFailure.Transport else OperatorTeamFailure.Unexpected

/** `operatorTeamApi.ts`'s own `OperatorRoleSeatDto`, mirrored field for field. */
@Serializable
private data class OperatorRoleSeatWireDto(
    val roleName: String,
    val holdsSeat: Boolean,
)

/** `operatorTeamApi.ts`'s own `OperatorTeamMemberDto`. */
@Serializable
private data class OperatorTeamMemberWireDto(
    val operatorId: String,
    val displayName: String? = null,
    val email: String? = null,
    val roles: List<OperatorRoleSeatWireDto> = emptyList(),
)

/**
 * `operatorTeamApi.ts`'s own `OperatorTeamResponseDto`. [operators] carries no default — the same
 * `KtorBookingsApiTest`/`KtorConversationsApiTest` lesson this file's own class-level doc comment
 * cites: a wrapper field that defaults to empty makes a `200` with an unrelated body indistinguishable
 * from a genuinely empty roster, which is exactly the shape-mismatch this adapter exists to catch.
 */
@Serializable
private data class OperatorTeamResponseWireDto(
    val operators: List<OperatorTeamMemberWireDto>,
)

/** `operatorTeamApi.ts`'s own `RoleSeatAssignmentSummaryDto`. */
@Serializable
private data class RoleSeatAssignmentSummaryWireDto(
    val roleName: String,
    val heldSeats: Int,
    val limit: Int,
    val overLimit: Boolean,
)

/** `operatorTeamApi.ts`'s own `SeatAssignmentSummaryDto`. [roles] carries no default, for the identical
 * shape-mismatch reason [OperatorTeamResponseWireDto.operators] does not. */
@Serializable
private data class SeatAssignmentSummaryWireDto(
    val roles: List<RoleSeatAssignmentSummaryWireDto>,
)

/** `operatorTeamApi.ts`'s own `createOperatorInvite` request body — `{roleName, email}`. */
@Serializable
private data class CreateOperatorInviteRequestWireDto(
    val roleName: String,
    val email: String,
)

/** `operatorTeamApi.ts`'s own `CreateOperatorInviteResponseDto`, mirrored field for field — [code] is
 * the plaintext invite code, present in this one response only. */
@Serializable
private data class CreateOperatorInviteResponseWireDto(
    val operatorInviteId: String,
    val code: String,
    val expiresAt: String,
    val sendFailed: Boolean,
)

/** RFC 7807, read for exactly the one field a refusal needs — the identical
 * `KtorConversationsApi.ProblemDetailsWireDto` shape, restated here rather than shared: each adapter in
 * `:core:network` keeps its own private copy today (`KtorOwnAnalyticsApi`'s own copy is the other
 * precedent), so this file can be read end to end without a jump to a shared type. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

private fun OperatorRoleSeatWireDto.toDomain() = OperatorRoleSeat(roleName = roleName, holdsSeat = holdsSeat)

private fun OperatorTeamMemberWireDto.toDomain() =
    OperatorTeamMember(
        operatorId = operatorId,
        displayName = displayName,
        email = email,
        roles = roles.map { it.toDomain() },
    )

private fun RoleSeatAssignmentSummaryWireDto.toDomain() =
    RoleSeatSummary(roleName = roleName, heldSeats = heldSeats, limit = limit, overLimit = overLimit)

/** `operatorTeamApi.ts`'s own `OperatorInviteListEntryDto`, mirrored field for field. [status] is a
 * string enum sent as `OperatorInviteListStatus`'s member name; an unrecognised member fails
 * deserialisation, which the read's own `catch` turns into [OperatorTeamFailure.Unexpected] — the
 * identical shape-mismatch posture the roster reads above already take, never a silent fallback. */
@Serializable
private enum class OperatorInviteStatusWireDto {
    Sent,
    SendFailed,
    Revoked,
    Redeemed,
    Expired,
}

/** `operatorTeamApi.ts`'s own `OperatorInviteListEntryDto`. [smtpErrorCode] is nullable and present only
 * for a [OperatorInviteStatusWireDto.SendFailed] row. */
@Serializable
private data class OperatorInviteListEntryWireDto(
    val operatorInviteId: String,
    val email: String,
    val createdAt: String,
    val expiresAt: String,
    val status: OperatorInviteStatusWireDto,
    val smtpErrorCode: String? = null,
)

/** `operatorTeamApi.ts`'s own `ListOperatorInvitesResponseDto`. [invites] carries no default, for the
 * identical shape-mismatch reason [OperatorTeamResponseWireDto.operators] does not — a `200` with an
 * unrelated body must be caught, never read as a genuinely empty invite list. */
@Serializable
private data class ListOperatorInvitesResponseWireDto(
    val invites: List<OperatorInviteListEntryWireDto>,
)

private fun OperatorInviteStatusWireDto.toDomain(): OperatorInviteStatus =
    when (this) {
        OperatorInviteStatusWireDto.Sent -> OperatorInviteStatus.Sent
        OperatorInviteStatusWireDto.SendFailed -> OperatorInviteStatus.SendFailed
        OperatorInviteStatusWireDto.Revoked -> OperatorInviteStatus.Revoked
        OperatorInviteStatusWireDto.Redeemed -> OperatorInviteStatus.Redeemed
        OperatorInviteStatusWireDto.Expired -> OperatorInviteStatus.Expired
    }

private fun OperatorInviteListEntryWireDto.toDomain() =
    OperatorInviteListItem(
        operatorInviteId = operatorInviteId,
        email = email,
        createdAt = createdAt,
        expiresAt = expiresAt,
        status = status.toDomain(),
        smtpErrorCode = smtpErrorCode,
    )
