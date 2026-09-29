package ago.chat.android.core.domain.persons

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-162`/`adr/0184`: the account's person registry, read for display — declared here and implemented
 * in `:core:network` (`KtorPersonsApi`), the identical `:core:domain` port / `:core:network` adapter
 * split every other chat-side read on this app already establishes
 * ([ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi]'s own doc comment). A dependency-rule
 * port, not a direct `HttpClient` in the view models that need it, for the identical reason that class's
 * own doc comment states: an untestable view model, and HTTP-shaped decisions leaking into `:app`.
 *
 * **Why this exists at all.** `adr/0184` moved the Person - display name included - out of
 * `Ago.Calendar.*` and into `Ago.Chat.*`'s own registry; the calendar's own contacts/confirmed-bookings
 * reads now carry only an opaque `personId`, never a name. This port is the one place this app reads a
 * name back *for* that id, so `ago.chat.android.bookings.ContactsViewModel`/`ConfirmedBookingsViewModel`
 * can display-merge it onto a calendar row client-side - decision 4 of that ADR ("reads are display-only
 * and console-side"), read onto this Android client the same way `ago-console` reads it for its own
 * screens. The calendar itself never calls this - only the two operator UIs do.
 *
 * A GET against `Ago.Chat.Api`'s own origin (`config.apiBaseUrl`), **not** the calendar's
 * (`config.calendarApiBaseUrl`) - chat owns the Person now, so this is one more endpoint on the same
 * origin [ago.chat.android.core.domain.conversations.ConversationsApi] already reads, never a new base
 * URL the way [ago.chat.android.core.domain.bookings.BookingsApi] needed one for the calendar.
 */
public interface PersonsApi {
    /**
     * `GET /api/v1/persons?ids=a,b,c` - the batch a screen needs, in one request
     * (`Ago.Chat.Api.Persons.PersonEndpoints`). A person id with nobody behind it in this account is
     * simply absent from [PersonsResult.Loaded.persons], never an error for the whole batch - the caller
     * decides what an absent id means for its own row (this port's own callers fall back to the emoji
     * pair or the identifier, never to a fabricated name).
     */
    public suspend fun fetchPersons(personIds: List<String>): PersonsResult

    /**
     * `26-269`: `GET /api/v1/persons/{personId}/conversations` — the client-detail hub's own "which
     * dialog do I open for this person" read (`docs/backlog/26-269-clients-redesign.md` §5/§8#2). The
     * calendar's own reads only ever hand this app a bare `personId`, never a `conversationId` — the
     * existing visitor-history read
     * ([ago.chat.android.core.domain.conversations.ConversationsApi], conceptually — this app has no
     * direct caller of it today) starts from a conversation already in hand, so it cannot serve "given a
     * client, which conversation is theirs" the way this call does.
     *
     * [PersonConversationsResult.Loaded.conversations] arrives **already ordered** — the active
     * conversation first if one exists, otherwise the most recently active — server-side
     * (`GetPersonConversationsHandler`'s own doc comment, `ago-chat`), so `conversations.firstOrNull()` is
     * always the one to open; this port does no re-sorting of its own. An empty list is a real, honest
     * fact (a `26-268` manual client has no conversation at all), not a failure — the caller hides its own
     * «Открыть диалог» action rather than greying it, never treating this as [PersonConversationsResult.Failed].
     */
    public suspend fun fetchPersonConversations(personId: String): PersonConversationsResult
}

/**
 * `PersonEndpoints.PersonsResponse` reduced to the one field either calendar screen actually renders -
 * [displayName]. [ago.chat.android.core.domain.persons.PersonProfile.personId] is carried back so the
 * caller can match each answer to the row it came from; the contact-channel list
 * (`PersonProfileDto.Channels`) is on the wire too and left off here on purpose - neither calendar
 * screen has a slot for it, the identical "no field with nothing that reads it" discipline
 * [ago.chat.android.core.domain.bookings.Contact]'s own doc comment states.
 */
public data class PersonProfile(
    val personId: String,
    /** The person's most recently recorded name, or `null` when nobody has recorded one -
     * [PersonProfile]'s own doc comment on why this is never invented from anything else. */
    val displayName: String?,
    /** `26-203`: `PersonProfileDto.EmojiCreature` (`26-202`'s own additive pair on chat's visitor row,
     * `Ago.Chat.Application.UseCases.GetPersons.PersonProfileDto`) - the identical operator-side memory
     * aid [ago.chat.android.core.domain.conversations.ConversationSummary.emojiCreature] and
     * [ago.chat.android.core.domain.restrictions.VisitorRestriction.emojiCreature] already carry for their
     * own screens, read here so a calendar-side caller with only an opaque `personId` (`adr/0184`) can
     * fall back to the same pair rather than a bare id - never a fabricated name and never hash-derived
     * from [personId]. `null` for a visitor row that predates the pair. */
    val emojiCreature: String? = null,
    /** The other half of [emojiCreature]'s own pair - see its doc comment, which this parameter shares in
     * full. */
    val emojiFood: String? = null,
)

/** What asking for a batch of persons came back with - the identical two-arm shape
 * [ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult] already establishes for the same
 * chat origin: this app is never deployed without `Ago.Chat.Api` reachable, unlike the calendar's own
 * reads, so there is no third "not configured" arm to carry here. */
public sealed interface PersonsResult {
    public data class Loaded(
        val persons: List<PersonProfile>,
    ) : PersonsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : PersonsResult
}

/**
 * `26-269`: one of a client's own conversations — `Ago.Chat.Api.Persons.PersonEndpoints.PersonConversationDto`
 * verbatim, field for field, since the client-detail hub has a slot for every one of them (unlike
 * [PersonProfile], nothing here is trimmed off the wire shape).
 */
public data class PersonConversation(
    val conversationId: String,
    /** The raw `ConversationState` wire name — carried alongside [isActive] rather than discarded, the
     * identical "collapse only the presentation, never the fact" rule
     * [ago.chat.android.core.domain.bookings.Contact]'s own warning-glyph doc comment states for its own
     * pair of phone facts. Unused by this app's own hub today (it reads [isActive] alone), kept because a
     * caller that ever needs to tell `Waiting` from `Assigned` must not find only the collapsed view. */
    val state: String,
    /** `state != "Closed"`, computed server-side — the one boolean the hub actually branches on ("open
     * normally, or read-only"), the identical reasoning `PersonConversationDto`'s own doc comment states
     * for why this rides the wire pre-computed rather than every client re-deriving the same one-line
     * predicate. */
    val isActive: Boolean,
    val startedAt: String,
    val closedAt: String?,
    val lastActivityAt: String,
)

/**
 * `26-269`: what asking for one client's own conversations came back with — the identical two-arm shape
 * [PersonsResult] already establishes for the same `Ago.Chat.Api` origin, for the identical reason: this
 * app is never deployed without that origin reachable, so there is no third "not configured" arm here
 * either.
 */
public sealed interface PersonConversationsResult {
    /** Ordered server-side, active-first-else-most-recent — see [PersonsApi.fetchPersonConversations]'s
     * own doc comment. Empty is a real fact (no conversation at all yet), never folded into [Failed]. */
    public data class Loaded(
        val conversations: List<PersonConversation>,
    ) : PersonConversationsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : PersonConversationsResult
}
