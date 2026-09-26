package ago.chat.android.core.domain.bookings

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * `26-51`: digits-only, in the business's own zone — **not** the device's, the one deliberate
 * divergence from [ago.chat.android.thread.ThreadScreen]'s own `clockTimeOrNull`, whose doc comment
 * states the opposite rule for the opposite reason ("the operator reading this screen, not the
 * visitor's"). A booking is an appointment *at the shop*; converting it onto whichever zone the phone
 * happens to be carried into would render a different clock face for the same appointment depending on
 * where the operator is standing, which is wrong in exactly the way a message timestamp converting to
 * the visitor's own zone would not be.
 *
 * There is no separate IANA zone id to convert into, and inventing one would be worse than not
 * converting at all: `Ago.Calendar.Contracts.ConfirmedBookingResponse` carries no time zone field
 * (`docs/backlog/26-51-*.md` states no wire change for this item), and
 * `ConfirmedBookingReadStore.ToRow` writes `StartsAt`/`EndsAt` as `DateTimeKind.Utc` — a real offset,
 * verified against that file's own source, not assumed. Per `ago-root/CLAUDE.md` rule 11 ("render in
 * the user's IANA zone when supplied and otherwise render UTC labelled as UTC"), the honest choice
 * absent a supplied zone is to render *exactly the offset the value already carries* — [OffsetDateTime.toLocalTime]
 * reads the wall-clock time already embedded in [iso], with no [java.time.ZoneId] conversion of any
 * kind. That happens to always be UTC today, and this function does not hard-code that fact: the day a
 * future item teaches the backend to carry each calendar's own offset on the wire, this function's
 * behaviour is already correct for it with no change here, because it was never converting to a zone at
 * all - only ever reading the one already attached.
 *
 * This is also what makes the item's own Done-when box checkable at all: setting a test clock or the
 * device's default zone to something else cannot change this function's output, because no zone read
 * (`ZoneId.systemDefault()` or otherwise) happens anywhere in it — see the class-level test that
 * proves exactly that.
 */
public fun businessLocalTimeOrNull(iso: String): String? =
    runCatching { OffsetDateTime.parse(iso).toLocalTime().format(BUSINESS_CLOCK_FORMAT) }.getOrNull()

/**
 * `26-163`: the weekday of a business-local `YYYY-MM-DD`, in the `0 = Sunday` convention every
 * `bookings_weekday_*` array and `ConfirmedBookingResponse.Weekday` already share - or `null` when the
 * date fails to parse, the identical "never invented, rendered honestly" posture [businessLocalTimeOrNull]
 * takes above.
 *
 * **A date-only derivation, on purpose, and why that is not the trap `docs/conventions/date-and-time.md`
 * warns about.** [ConfirmedBooking.weekday] is computed server-side because the console's own JavaScript
 * would otherwise parse a bare date string as UTC midnight and, west of Greenwich, land on the previous
 * day. That trap needs an *instant* to fall into: a `YYYY-MM-DD` read as a [LocalDate] carries no
 * instant, no offset and no zone, so its weekday is a calendrical fact that cannot move with the device -
 * `2026-09-29` is a Tuesday everywhere. `PendingBookingResponse` carries no `weekday` of its own, and this
 * is the one honest way to render the pending sheet's «Вторник, 29 сентября 2026» line without asking the
 * calendar for a field whose whole value is guarding against a mistake [LocalDate] cannot make.
 *
 * [java.time.DayOfWeek] numbers Monday `1` through Sunday `7`; the modulo folds Sunday onto `0`.
 */
public fun businessLocalWeekdayOrNull(localDate: String): Int? = runCatching { LocalDate.parse(localDate).dayOfWeek.value % 7 }.getOrNull()

private val BUSINESS_CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
