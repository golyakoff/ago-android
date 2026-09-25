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
