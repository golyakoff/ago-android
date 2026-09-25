package ago.chat.android.core.domain.calendarsetup

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-141`: the port the Настройка / Календари screen (`26-142`, `:app`) reads and writes through —
 * declared here and implemented in `:core:network` (`KtorCalendarSetupApi`), the identical split
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi] and
 * [ago.chat.android.core.domain.bookings.BookingsApi] already establish. The dependency rule is what
 * puts it here: a view model holding an `HttpClient` directly could not be tested without one, and
 * every HTTP-shaped decision (which status means what, which base URL to call) belongs on the far side
 * of this interface, in the adapter.
 *
 * **Its own port, not more methods on [ago.chat.android.core.domain.bookings.BookingsApi].** That port
 * is `Ago.Calendar.Api`'s `/console` read surface as the *bookings* screens use it; these are the
 * tenant-configuration writes (embedding origins, the calendar list) a single Setup screen owns, gated
 * server-side on `calendar:configure` alone. The classification enum [BookingsQueueFailure] and the
 * write-result shape [BookingActionResult] *are* reused across the boundary rather than copied, and
 * deliberately: despite their names they answer only "is it me, or is it broken" and
 * "succeeded / refused-with-a-server-sentence / failed", the two questions this port needs in exactly
 * those shapes (their own doc comments already say the names are historical artifacts of the pending
 * queue being their first caller).
 */
public interface CalendarSetupApi {
    /**
     * `GET /api/v1/console/configuration` — the same read `ago-console`'s own `CalendarSetupPage`
     * makes, reduced by the adapter to the four things this screen draws: the tenant name, the public
     * embed key, the allowed-origins list and the calendar roster. The workers, the services and the
     * per-calendar working hours the same document also carries are for other screens
     * (`26-139`/`26-96`/`26-97`) and are read through their own ports, so this adapter declares and
     * discards none of them (`ignoreUnknownKeys`).
     */
    public suspend fun fetchSetup(): TenantSetupResult

    /**
     * `PUT /api/v1/console/configuration/allowed-origins` — replaces the whole list every time
     * (`SetAllowedOriginsRequest.Origins`), the identical replace-semantics `ago-console`'s own
     * `setAllowedOrigins` uses. Answers `204`, a server-authored refusal shown verbatim, or a failure —
     * the three-way [BookingActionResult] question every write in this product already reduces to. A
     * malformed origin the server rejects arrives as [BookingActionResult.Refused] carrying the
     * server's own sentence, which is exactly why this write must not invent its own validation
     * messages.
     */
    public suspend fun saveAllowedOrigins(origins: List<String>): BookingActionResult

    /**
     * `POST /api/v1/console/calendars` (`CreateCalendarRequest: Name, TimeZone, Publish`) — the one
     * write that takes a timezone, and the reason [timeZone] is a parameter here but not on
     * [updateCalendar]: a calendar's zone is fixed at creation and never edited afterwards
     * (`UpdateCalendarRequest` omits it by design). The server answers `201 Created` with the new id;
     * this app re-reads [fetchSetup] after every write rather than patching one row from the echo, so
     * the id is not carried back — any `2xx` is [BookingActionResult.Succeeded].
     */
    public suspend fun createCalendar(
        draft: CalendarDraft,
        timeZone: String,
    ): BookingActionResult

    /**
     * `PUT /api/v1/console/calendars/{calendarId}` (`UpdateCalendarRequest: Name, Publish`) — the name
     * and the published flag, and **deliberately not the timezone**: a calendar is re-titled or
     * published/unpublished where it is, never moved to another zone (the server contract has no field
     * for it), so sending one here would offer a choice that cannot happen. There is by design **no
     * delete-calendar endpoint** to mirror — a calendar is unpublished, never removed.
     */
    public suspend fun updateCalendar(
        calendarId: String,
        draft: CalendarDraft,
    ): BookingActionResult
}

/**
 * `26-141`: the four things the Setup screen draws, lifted out of the far larger
 * `TenantConfigurationResponse`. Everything on it is what a human reads or edits on this one screen; the
 * workers, services and working hours the same server document also carries belong to other screens and
 * are not modelled here.
 */
public data class TenantSetup(
    /** The shop's own name, shown as read-only context. */
    val tenantName: String,
    /** What the shop pastes into its page's script tag — shown only here, never editable
     * (`TenantConfiguration.publicKey`'s own console remark: the console is the only place it appears). */
    val publicKey: String,
    /** The origins the widget is allowed to load from, in the order the server returned them. Replaced
     * wholesale by [CalendarSetupApi.saveAllowedOrigins]. */
    val allowedOrigins: List<String>,
    /** Every calendar this tenant has, in the server's own order. */
    val calendars: List<ConfiguredCalendar>,
)

/**
 * `26-141`: one calendar as the Setup list draws it — `id · name · zone · published`. A reduced view of
 * `ConfiguredCalendarResponse`: the `workerIds` and per-calendar `workingHours` it also carries are the
 * Masters (`26-139`) and Часы (`26-97`) screens' concern, read through their own ports, so they are not
 * fields here.
 */
public data class ConfiguredCalendar(
    val id: String,
    val name: String,
    /** IANA zone id (`"Europe/Moscow"`) — carried verbatim. Turning it into a localized label, and
     * offering the list of zones on the create form, is the UI's job (`26-142`), never this port's. */
    val timeZone: String,
    val published: Boolean,
)

/**
 * `26-141`: what a human typed into the calendar form — the fields **create and edit share**. The
 * timezone is not on it, because it is create-only and belongs to exactly one of the two calls: it
 * travels as a separate parameter to [CalendarSetupApi.createCalendar] and has no place in
 * [CalendarSetupApi.updateCalendar] at all, which is the create-only rule expressed in the signatures
 * rather than left to a comment.
 */
public data class CalendarDraft(
    val name: String,
    val published: Boolean,
)

/**
 * `26-141`: what reading the tenant configuration came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.schedule.WorkingHoursResult] and
 * [ago.chat.android.core.domain.bookings.PendingBookingsResult] already establish, restated rather than
 * shared because [Loaded] carries [TenantSetup], a type with no field in common with either sibling.
 */
public sealed interface TenantSetupResult {
    public data class Loaded(
        val setup: TenantSetup,
    ) : TenantSetupResult

    /** This deployment does not run AGO Calendar at all
     * ([ago.chat.android.core.domain.bookings.PendingBookingsResult.NotConfigured]'s own doc comment
     * explains the state) — an honest fact, not a failure and not a spinner. */
    public data object NotConfigured : TenantSetupResult

    /** [BookingsQueueFailure] reused again — this read reduces to the same "is it me, or is it broken"
     * two-way question every other read in this product answers with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : TenantSetupResult
}
