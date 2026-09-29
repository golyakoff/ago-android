package ago.chat.android.core.network.persons

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.persons.PersonConversation
import ago.chat.android.core.domain.persons.PersonConversationsResult
import ago.chat.android.core.domain.persons.PersonProfile
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-162`: the adapter behind [PersonsApi] - the same "the whole status-code-to-meaning mapping lives
 * here, and only here" shape [ago.chat.android.core.network.visitorsummary.KtorVisitorSummaryApi]'s own
 * doc comment states for the same `Ago.Chat.Api` origin.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method - the identical reason every other adapter on this origin gives.
 *
 * An empty [PersonsApi.fetchPersons] request answers [PersonsResult.Loaded] with an empty list and makes
 * no network call at all - a screen with nothing loaded yet (or a calendar read that returned zero rows)
 * has no ids to ask about, and `?ids=` with nothing after it is not a request worth making.
 */
public class KtorPersonsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : PersonsApi {
    override suspend fun fetchPersons(personIds: List<String>): PersonsResult {
        val distinctIds = personIds.distinct()
        if (distinctIds.isEmpty()) return PersonsResult.Loaded(emptyList())

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/persons") {
                    parameter("ids", distinctIds.joinToString(","))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return PersonsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return PersonsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            PersonsResult.Loaded(response.body<PersonsWireDto>().persons.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "nobody found" - the identical
            // `KtorVisitorSummaryApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            PersonsResult.Failed(NetworkFailure.from(failure))
        }
    }

    /**
     * `26-269`: `GET /api/v1/persons/{personId}/conversations` — the identical classify-never-invent
     * shape [fetchPersons] above establishes, restated for this second endpoint on the same origin rather
     * than folded into it: the two reads share no request shape beyond "a `GET` against this same base
     * URL" ([KtorBookingsApi]'s own doc comment states the identical reasoning for not sharing a helper
     * across its own several reads).
     */
    override suspend fun fetchPersonConversations(personId: String): PersonConversationsResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/persons/$personId/conversations")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return PersonConversationsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return PersonConversationsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            PersonConversationsResult.Loaded(response.body<PersonConversationsWireDto>().conversations.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no conversations yet" - the identical
            // `fetchPersons`/`shapeGuard.ts` lesson, read onto this endpoint.
            PersonConversationsResult.Failed(NetworkFailure.from(failure))
        }
    }
}

/** `Ago.Chat.Api.Persons.PersonEndpoints.PersonsResponse`, reduced to what [PersonsApi] carries. */
@Serializable
private data class PersonsWireDto(
    val persons: List<PersonProfileWireDto> = emptyList(),
)

/** `Ago.Chat.Application.UseCases.GetPersons.PersonProfileDto`, reduced to [PersonProfile]'s own fields -
 * `channels`/`firstSeenAt`/`lastSeenAt` are all on the wire and simply omitted here, the identical
 * `ignoreUnknownKeys`-backed reduction [PersonProfile]'s own doc comment explains.
 *
 * `26-203`: `emojiCreature`/`emojiFood` join the same additive way - `26-202`'s own pair, already on this
 * exact wire response (`PersonProfileDto.EmojiCreature`/`.EmojiFood`), simply never read on this side of
 * the client until now. */
@Serializable
private data class PersonProfileWireDto(
    val personId: String,
    val displayName: String? = null,
    val emojiCreature: String? = null,
    val emojiFood: String? = null,
)

private fun PersonProfileWireDto.toDomain() =
    PersonProfile(
        personId = personId,
        displayName = displayName,
        emojiCreature = emojiCreature,
        emojiFood = emojiFood,
    )

/** `Ago.Chat.Api.Persons.PersonEndpoints.PersonConversationsResponse`, verbatim. */
@Serializable
private data class PersonConversationsWireDto(
    val conversations: List<PersonConversationWireDto> = emptyList(),
)

/** `Ago.Chat.Application.UseCases.GetPersonConversations.PersonConversationDto`, field for field - see
 * [PersonConversation]'s own doc comment for why nothing here is trimmed off the wire shape. */
@Serializable
private data class PersonConversationWireDto(
    val conversationId: String,
    val state: String,
    val isActive: Boolean,
    val startedAt: String,
    val closedAt: String? = null,
    val lastActivityAt: String,
)

private fun PersonConversationWireDto.toDomain() =
    PersonConversation(
        conversationId = conversationId,
        state = state,
        isActive = isActive,
        startedAt = startedAt,
        closedAt = closedAt,
        lastActivityAt = lastActivityAt,
    )
