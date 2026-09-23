package ago.chat.android.bookings

/** Записи's own three segments — Ожидают (`26-48`), Утверждены (`26-51`) и Клиенты (`26-52`). A plain
 * UI-layer enum, not a `:core:domain` type: unlike [ago.chat.android.core.domain.permissions.Permission],
 * nothing outside this screen's own composables and view models needs to know these names exist. */
internal enum class BookingsTab {
    Pending,
    Confirmed,
    Clients,
}

/**
 * `26-51`/`26-52`: which segments this operator's own session draws, in [BookingsTab]'s own order — the
 * identical "a destination/segment with nothing behind it is not drawn" rule
 * [ago.chat.android.core.domain.navigation.visibleBottomDestinations] already applies one level up
 * (`docs/backlog/26-51-*.md`'s own Scope item 1). Пending is never gated here — every operator who can
 * reach Записи at all (`canSeeBookings`) may see the pending queue, since a `booking:confirm`/`reject`/
 * `cancel` holder's whole reason to be here is that queue.
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
): List<BookingsTab> =
    buildList {
        add(BookingsTab.Pending)
        if (showConfirmedSegment) add(BookingsTab.Confirmed)
        if (showClientsSegment) add(BookingsTab.Clients)
    }
