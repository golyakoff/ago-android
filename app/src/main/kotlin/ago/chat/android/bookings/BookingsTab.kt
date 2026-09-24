package ago.chat.android.bookings

/** Записи's own five segments — Ожидают (`26-48`), Утверждены (`26-51`), Клиенты (`26-52`), Услуги
 * (`26-96`) и Часы (`26-97`). A plain UI-layer enum, not a `:core:domain` type: unlike
 * [ago.chat.android.core.domain.permissions.Permission], nothing outside this screen's own composables
 * and view models needs to know these names exist. */
internal enum class BookingsTab {
    Pending,
    Confirmed,
    Clients,
    Services,

    /** `26-97`: the working-hours rules, with the Edit and Delete this product did not have until that
     * item. Placed here rather than behind «График мастера» (the `26-90` pass's item **E**, which does
     * not exist in this repository yet) because a correction nobody can reach is not a correction —
     * see [ago.chat.android.schedule.WorkingHoursBody]'s own doc comment. */
    Hours,
}

/**
 * `26-51`/`26-52`: which segments this operator's own session draws, in [BookingsTab]'s own order — the
 * identical "a destination/segment with nothing behind it is not drawn" rule
 * [ago.chat.android.core.domain.navigation.visibleBottomDestinations] already applies one level up
 * (`docs/backlog/26-51-*.md`'s own Scope item 1). Пending is never gated here — every operator who can
 * reach Записи at all (`canSeeBookings`) may see the pending queue, since a `booking:confirm`/`reject`/
 * `cancel` holder's whole reason to be here is that queue.
 *
 * `26-96` adds a fourth, [showServicesSegment], on
 * [Permission.CALENDAR_CONFIGURE][ago.chat.android.core.domain.permissions.Permission.CALENDAR_CONFIGURE]
 * **alone** - a fourth independent gate, not [showClientsSegment] reused: that one is
 * `calendar:configure` *or* `customer:read`, and an operator holding only `customer:read` may read the
 * customer base without being allowed to rewrite the tenant's own service dictionary
 * (`ago-console`'s own `CalendarServicesPage.tsx` gates on `calendar:configure` alone, and the server
 * refuses anything else regardless).
 *
 * `26-96` also makes this the app's interim home for Услуги: `docs/navigation.md` designs one under a
 * «Настройка записи» hub that does not exist in this app yet, and a segment beside Клиенты reaches the
 * screen today without inventing that hub ahead of the item that scopes it.
 *
 * [showConfirmedSegment] is [Permission.CUSTOMER_READ][ago.chat.android.core.domain.permissions.Permission.CUSTOMER_READ]
 * alone. [showClientsSegment] is [Permission.CALENDAR_CONFIGURE][ago.chat.android.core.domain.permissions.Permission.CALENDAR_CONFIGURE]
 * **or** [Permission.CUSTOMER_READ][ago.chat.android.core.domain.permissions.Permission.CUSTOMER_READ] —
 * a third, genuinely distinct gate from the other two, matching `CalendarContactsPage.tsx:48` exactly
 * (`docs/backlog/26-52-*.md`'s own Scope item 1: "the segmented-control-building logic genuinely has to
 * compute all three independently rather than assume a fixed set"). Both booleans are computed once by
 * the caller ([ago.chat.android.shell.AppShellScreen]) from the already-known
 * [ago.chat.android.core.domain.permissions.OperatorPermissions.Known] rather than re-fetched here — a
 * plain function over two `Boolean`s is what makes this list buildable from a unit test with no Hilt
 * component and no second network call.
 */
internal fun visibleBookingsTabs(
    showConfirmedSegment: Boolean,
    showClientsSegment: Boolean,
    showServicesSegment: Boolean,
    // `26-97`: a fifth, independently computed gate - `calendar:configure` alone, which is what
    // `PUT`/`DELETE /working-hours/{ruleId}` check server-side. Narrower than Клиенты's own
    // `calendar:configure` OR `customer:read`, so it genuinely has to be its own boolean rather
    // than reuse either sibling's.
    showHoursSegment: Boolean,
): List<BookingsTab> =
    buildList {
        add(BookingsTab.Pending)
        if (showConfirmedSegment) add(BookingsTab.Confirmed)
        if (showClientsSegment) add(BookingsTab.Clients)
        if (showServicesSegment) add(BookingsTab.Services)
        if (showHoursSegment) add(BookingsTab.Hours)
    }
