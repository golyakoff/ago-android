package ago.chat.android.core.network.team

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.ChangeOperatorRoleResult
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorInviteEffectiveStatus
import ago.chat.android.core.domain.team.OperatorInviteListItem
import ago.chat.android.core.domain.team.OperatorInviteStatus
import ago.chat.android.core.domain.team.OperatorInvitesResult
import ago.chat.android.core.domain.team.OperatorRoleSeat
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.ROLE_ADMIN
import ago.chat.android.core.domain.team.ROLE_OPERATOR
import ago.chat.android.core.domain.team.RemoveOperatorResult
import ago.chat.android.core.domain.team.RevokeInviteResult
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.core.domain.team.SeatSummaryResult
import ago.chat.android.core.domain.team.ToggleOperatorSeatResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
        roleNames: Set<String>,
        email: String,
    ): CreateInviteResult {
        val siteId = activeSite.currentSiteId() ?: return CreateInviteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/$siteId/operator-invites") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOperatorInviteRequestWireDto(roleNames = roleNames.toList(), email = email))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return CreateInviteResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            val problem =
                try {
                    response.body<ProblemDetailsWireDto>()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
            // `26-241`: a `402` naming a per-role seat pool (`type`) becomes a typed
            // [CreateInviteResult.RoleSeatFull] the sheet can word itself — never folded into the
            // words-verbatim [Refused] arm, so it branches on the `type`, not the server's `detail`
            // (`api-design.md`). Any other refusal with a `detail` stays [Refused] (`InvalidEmail`
            // chief among them); a refusal with neither a known code nor a `detail` is [Failed].
            inviteSeatFullRole(problem?.type)?.let { return CreateInviteResult.RoleSeatFull(it) }
            return problem?.detail?.let { CreateInviteResult.Refused(it) }
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

    /** `POST /api/v1/sites/{siteId}/operators/{operatorId}/role` — `26-253`. `204` is
     * [ChangeOperatorRoleResult.Changed]; a refusal is read for its RFC 7807 `type` and branched by code
     * (`api-design.md`), never by the server's `detail`. The two state-conflict codes become typed arms;
     * any other refusal with a `detail` is shown verbatim, and a refusal with neither is [Failed]. */
    override suspend fun changeOperatorRole(
        operatorId: String,
        newRoleName: String,
    ): ChangeOperatorRoleResult {
        val siteId = activeSite.currentSiteId() ?: return ChangeOperatorRoleResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/$siteId/operators/$operatorId/role") {
                    contentType(ContentType.Application.Json)
                    setBody(ChangeOperatorRoleRequestWireDto(roleName = newRoleName))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ChangeOperatorRoleResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return ChangeOperatorRoleResult.Changed
        }

        val problem = readProblem(response)
        return when (problem?.type) {
            OPERATOR_LAST_MANAGER_CODE -> ChangeOperatorRoleResult.LastManager
            OPERATOR_ADMIN_LIMIT_CODE -> ChangeOperatorRoleResult.AdminSeatFull
            else ->
                problem?.detail?.let { ChangeOperatorRoleResult.Refused(it) }
                    ?: ChangeOperatorRoleResult.Failed(NetworkFailure.ServerError(response.status.value))
        }
    }

    /** `POST /api/v1/sites/{siteId}/operators/{operatorId}/remove` — `26-253`. `204` is
     * [RemoveOperatorResult.Removed]; `Operator.IsLastManager` becomes the typed [RemoveOperatorResult.LastManager],
     * any other `detail`-bearing refusal is shown verbatim, and a refusal with neither is [Failed]. */
    override suspend fun removeOperator(operatorId: String): RemoveOperatorResult {
        val siteId = activeSite.currentSiteId() ?: return RemoveOperatorResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/$siteId/operators/$operatorId/remove")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RemoveOperatorResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return RemoveOperatorResult.Removed
        }

        val problem = readProblem(response)
        return when (problem?.type) {
            OPERATOR_LAST_MANAGER_CODE -> RemoveOperatorResult.LastManager
            else ->
                problem?.detail?.let { RemoveOperatorResult.Refused(it) }
                    ?: RemoveOperatorResult.Failed(NetworkFailure.ServerError(response.status.value))
        }
    }

    /** `POST /api/v1/sites/{siteId}/operators/{operatorId}/seat` — `26-253`. `204` is
     * [ToggleOperatorSeatResult.Toggled]; the two per-role seat-full `402`s become a typed
     * [ToggleOperatorSeatResult.SeatFull] naming the pool (branching on the `type`, not the `detail`),
     * any other `detail`-bearing refusal is shown verbatim, and a refusal with neither is [Failed]. */
    override suspend fun toggleOperatorSeat(
        operatorId: String,
        roleName: String,
        holdsSeat: Boolean,
    ): ToggleOperatorSeatResult {
        val siteId = activeSite.currentSiteId() ?: return ToggleOperatorSeatResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/$siteId/operators/$operatorId/seat") {
                    contentType(ContentType.Application.Json)
                    setBody(ToggleOperatorSeatRequestWireDto(roleName = roleName, holdsSeat = holdsSeat))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ToggleOperatorSeatResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return ToggleOperatorSeatResult.Toggled
        }

        val problem = readProblem(response)
        return seatFullRole(problem?.type)?.let { ToggleOperatorSeatResult.SeatFull(it) }
            ?: problem?.detail?.let { ToggleOperatorSeatResult.Refused(it) }
            ?: ToggleOperatorSeatResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    /** The RFC 7807 body of a refusal, or `null` when the response carried none (or one this adapter could
     * not read) — the identical read `createInvite`'s own refusal branch already makes inline, extracted
     * here because the three `26-253` writes each need the same two fields (`type`/`detail`). */
    private suspend fun readProblem(response: HttpResponse): ProblemDetailsWireDto? =
        try {
            response.body<ProblemDetailsWireDto>()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
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

/** `ago-chat`'s own `OperatorTeamMemberDto`. `26-263`: [joinedAt] is the redeemed instant of the invite
 * this member joined through, nullable and defaulted — `null` for the founder (never invited). */
@Serializable
private data class OperatorTeamMemberWireDto(
    val operatorId: String,
    val displayName: String? = null,
    val email: String? = null,
    val roles: List<OperatorRoleSeatWireDto> = emptyList(),
    val joinedAt: String? = null,
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

/** `operatorTeamApi.ts`'s own `createOperatorInvite` request body — `26-241` took it to a set of role
 * names (`{roleNames, email}`); the legacy single-`roleName` body still works server-side, but this
 * adapter always sends the plural form now (a one-role invite is a one-element list). */
@Serializable
private data class CreateOperatorInviteRequestWireDto(
    val roleNames: List<String>,
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

/** RFC 7807, read for the two fields a refusal needs — [detail] (shown verbatim for `InvalidEmail` and
 * the like) and, `26-241`, [type] (the machine-readable code the sheet branches on for a per-role
 * seat-full `402`, `api-design.md`). The identical `KtorConversationsApi.ProblemDetailsWireDto` shape,
 * restated here rather than shared: each adapter in `:core:network` keeps its own private copy today
 * (`KtorOwnAnalyticsApi`'s own copy is the other precedent), so this file can be read end to end
 * without a jump to a shared type. */
@Serializable
private data class ProblemDetailsWireDto(
    val type: String? = null,
    val detail: String? = null,
)

/** `26-241`: the two `type` values `CreateOperatorInviteHandler` refuses a *per-role* seat-full invite
 * with — `OperatorInvite.SeatLimitReached` (the Operator role's own pool is full) and
 * `OperatorInvite.AdminLimitReached` (the Admin role's own). Named here, in the one place the wire
 * shape is turned into meaning (`api-design.md`: clients branch on the `type`, never the message), so
 * neither the domain result nor the view model ever spells a raw code. Any other `type` maps to `null`
 * — the refusal falls through to the words-verbatim [CreateInviteResult.Refused]. */
private const val INVITE_OPERATOR_SEAT_FULL_CODE = "OperatorInvite.SeatLimitReached"
private const val INVITE_ADMIN_SEAT_FULL_CODE = "OperatorInvite.AdminLimitReached"

private fun inviteSeatFullRole(type: String?): String? =
    when (type) {
        INVITE_OPERATOR_SEAT_FULL_CODE -> ROLE_OPERATOR
        INVITE_ADMIN_SEAT_FULL_CODE -> ROLE_ADMIN
        else -> null
    }

/** `26-253`: `ago-console`'s own `changeOperatorRole` request body — `{roleName}`. */
@Serializable
private data class ChangeOperatorRoleRequestWireDto(
    val roleName: String,
)

/** `25-170`/`26-253`: `ago-console`'s own `toggleOperatorSeat` request body — `{roleName, holdsSeat}`, a
 * seat being a fact about one `(operator, role)` pairing. */
@Serializable
private data class ToggleOperatorSeatRequestWireDto(
    val roleName: String,
    val holdsSeat: Boolean,
)

/** `26-253`: the `type` codes the three writes branch on. `Operator.IsLastManager` (`ChangeOperatorRoleHandler`/
 * `RemoveOperatorHandler`'s shared last-manager guard) and the two per-role seat-full codes
 * (`ToggleOperatorSeatHandler`/`ChangeOperatorRoleHandler`) — named here, in the one place a wire shape
 * becomes meaning (`api-design.md`: branch on the `type`, never the message), so neither the domain
 * result nor the view model ever spells a raw code. `Operator.SeatLimitReached` is the Operator pool,
 * `Operator.AdminLimitReached` the Admin pool — the same two-pool split `INVITE_*_SEAT_FULL_CODE` draws
 * for the invite write's own distinct codes. */
private const val OPERATOR_LAST_MANAGER_CODE = "Operator.IsLastManager"
private const val OPERATOR_SEAT_LIMIT_CODE = "Operator.SeatLimitReached"
private const val OPERATOR_ADMIN_LIMIT_CODE = "Operator.AdminLimitReached"

private fun seatFullRole(type: String?): String? =
    when (type) {
        OPERATOR_SEAT_LIMIT_CODE -> ROLE_OPERATOR
        OPERATOR_ADMIN_LIMIT_CODE -> ROLE_ADMIN
        else -> null
    }

private fun OperatorRoleSeatWireDto.toDomain() = OperatorRoleSeat(roleName = roleName, holdsSeat = holdsSeat)

private fun OperatorTeamMemberWireDto.toDomain() =
    OperatorTeamMember(
        operatorId = operatorId,
        displayName = displayName,
        email = email,
        roles = roles.map { it.toDomain() },
        joinedAt = joinedAt,
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

/** `26-263`: the effective team-membership status `ago-chat`'s own `OperatorInviteEffectiveStatus` sends
 * as its enum member name — a **second, distinct** status from [OperatorInviteStatusWireDto] (the
 * delivery lifecycle). An unrecognised member fails deserialisation, which the read's own `catch` turns
 * into [OperatorTeamFailure.Unexpected] — the identical shape-mismatch posture [OperatorInviteStatusWireDto]
 * takes, never a silent fallback that would mis-section a real invite. */
@Serializable
private enum class OperatorInviteEffectiveStatusWireDto {
    Pending,
    InTeam,
    Removed,
    Revoked,
    Expired,
}

/** `ago-chat`'s own `OperatorInviteListEntryResponse`. [smtpErrorCode] is nullable and present only for a
 * [OperatorInviteStatusWireDto.SendFailed] row. `26-258`: [roles] is the invite's granted role set,
 * always present server-side (`[]` for none) but defaulted here for robustness against an older payload.
 * `26-263`: [effectiveStatus] is required (a `200` missing it is a shape mismatch, caught like any
 * other); [redeemedAt]/[removedAt] are nullable ISO instants, present only where the effective status
 * warrants them. */
@Serializable
private data class OperatorInviteListEntryWireDto(
    val operatorInviteId: String,
    val email: String,
    val createdAt: String,
    val expiresAt: String,
    val status: OperatorInviteStatusWireDto,
    val smtpErrorCode: String? = null,
    val roles: List<String> = emptyList(),
    val effectiveStatus: OperatorInviteEffectiveStatusWireDto,
    val redeemedAt: String? = null,
    val removedAt: String? = null,
    val revokedAt: String? = null,
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

private fun OperatorInviteEffectiveStatusWireDto.toDomain(): OperatorInviteEffectiveStatus =
    when (this) {
        OperatorInviteEffectiveStatusWireDto.Pending -> OperatorInviteEffectiveStatus.Pending
        OperatorInviteEffectiveStatusWireDto.InTeam -> OperatorInviteEffectiveStatus.InTeam
        OperatorInviteEffectiveStatusWireDto.Removed -> OperatorInviteEffectiveStatus.Removed
        OperatorInviteEffectiveStatusWireDto.Revoked -> OperatorInviteEffectiveStatus.Revoked
        OperatorInviteEffectiveStatusWireDto.Expired -> OperatorInviteEffectiveStatus.Expired
    }

private fun OperatorInviteListEntryWireDto.toDomain() =
    OperatorInviteListItem(
        operatorInviteId = operatorInviteId,
        email = email,
        createdAt = createdAt,
        expiresAt = expiresAt,
        status = status.toDomain(),
        smtpErrorCode = smtpErrorCode,
        roles = roles,
        effectiveStatus = effectiveStatus.toDomain(),
        redeemedAt = redeemedAt,
        removedAt = removedAt,
        revokedAt = revokedAt,
    )
