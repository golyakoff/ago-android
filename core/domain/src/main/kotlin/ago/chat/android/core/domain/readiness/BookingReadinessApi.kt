package ago.chat.android.core.domain.readiness

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `23-23`/`26-163`: the port [ago.chat.android.bookings.ReadinessViewModel] reads Записи's own
 * «Готовность» screen through — `GET /api/v1/console/booking-readiness`
 * (`Ago.Calendar.Api.Configuration.ConsoleEndpoints`), gated server-side on `calendar:configure` alone,
 * the identical gate every other Записи `⋮` config screen checks
 * ([ago.chat.android.core.domain.workers.WorkersApi]'s own doc comment). Its own port, not a method on
 * [ago.chat.android.core.domain.bookings.BookingsApi] or [ago.chat.android.core.domain.workers.WorkersApi]:
 * this reads a computed fact about a tenant's whole configuration, not one dictionary — the identical
 * "a different noun, its own port" reasoning [ago.chat.android.core.domain.workers.WorkersApi]'s own doc
 * comment already gives.
 *
 * **A read-only port.** Unlike [ago.chat.android.core.domain.workers.WorkersApi] or
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi], nothing here writes — the screen's own
 * «Исправить» action is an in-hub navigation swap to another config screen
 * ([ago.chat.android.bookings.ReadinessViewModel]'s own doc comment), never a call through this
 * interface.
 */
public interface BookingReadinessApi {
    /** The one read this screen opens on — re-read on every open of the screen and on retry, no polling,
     * no cache (`docs/design/26-154-*.md`'s own accepted "re-read on open" decision, Q4). */
    public suspend fun fetchReadiness(): BookingReadinessResult
}

/**
 * `23-23`: the six things `flows.md` 3.1's own scope names, classified here in `:core:domain` rather
 * than left as a raw wire string for a UI `when` to switch on — the identical "the classification lives
 * in `:core:domain`" call `26-163`'s own scope makes, one step further than
 * `ContactDetailsSection.fieldLabel`'s own UI-side `when` takes it for `ContactDetail.kind`. [Unknown] is
 * not a bug: a backend that adds a seventh precondition before this client is taught it must still
 * render something, and [PreconditionState.rawPrecondition] is what that something is.
 */
public enum class BookingPrecondition {
    WorkerOnCalendar,
    ServiceOffered,
    WorkingHoursConfigured,
    ScheduleSaved,
    SlotsMaterialized,
    CalendarPublished,

    /** An unrecognised wire spelling — never dropped. [PreconditionState.rawPrecondition] carries the
     * original string for whichever caller renders it as-is. */
    Unknown,
}

/**
 * One row of a [CalendarReadiness]'s own chain — [precondition]'s classification plus [isMet], and the
 * wire spelling verbatim in [rawPrecondition] for the one caller that needs it ([BookingPrecondition.Unknown]'s
 * own doc comment).
 */
public data class PreconditionState(
    val precondition: BookingPrecondition,
    val rawPrecondition: String,
    val isMet: Boolean,
)

/**
 * `Ago.Calendar.Contracts.CalendarReadinessResponse`, unreduced — one calendar's own bookability and its
 * six-step chain. [calendarId]/[calendarName] are both `null` on exactly the tenant-has-no-calendar
 * placeholder the server itself synthesises (`GetBookingReadinessHandler`'s own remarks) — never a real
 * calendar with no name, since every calendar the Настройка screen creates requires one. [isBookable] is
 * folded server-side, never re-folded here — the same "compute it once, so no caller folds six booleans
 * wrong" reasoning the server's own response carries.
 */
public data class CalendarReadiness(
    val calendarId: String?,
    val calendarName: String?,
    val isBookable: Boolean,
    val preconditions: List<PreconditionState>,
)

/**
 * `26-163`: what the «Готовность» screen's one read came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.workers.WorkersResult] establishes, restated here because [Loaded]
 * carries a different list.
 */
public sealed interface BookingReadinessResult {
    public data class Loaded(
        val calendars: List<CalendarReadiness>,
    ) : BookingReadinessResult

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.workers.WorkersResult.NotConfigured]'s own doc comment explains. */
    public data object NotConfigured : BookingReadinessResult

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : BookingReadinessResult
}
