package ago.chat.android.bookings

/** Записи's own two segments — Ожидают (`26-48`) and Утверждены (`26-51`). A plain UI-layer enum,
 * not a `:core:domain` type: unlike [ago.chat.android.core.domain.permissions.Permission], nothing
 * outside this screen's own composables and view models needs to know these names exist. */
internal enum class BookingsTab {
    Pending,
    Confirmed,
}

/**
 * `26-51`: which segments this operator's own session draws, in [BookingsTab]'s own order — the
 * identical "a destination/segment with nothing behind it is not drawn" rule
 * [ago.chat.android.core.domain.navigation.visibleBottomDestinations] already applies one level up
 * (`docs/backlog/26-51-*.md`'s own Scope item 1). Пending is never gated here — every operator who can
 * reach Записи at all (`canSeeBookings`) may see the pending queue, since a `booking:confirm`/`reject`/
 * `cancel` holder's whole reason to be here is that queue. [showConfirmedSegment] is [Permission.CUSTOMER_READ][ago.chat.android.core.domain.permissions.Permission.CUSTOMER_READ]
 * alone, computed once by the caller ([ago.chat.android.shell.AppShellScreen]) from the already-known
 * [ago.chat.android.core.domain.permissions.OperatorPermissions.Known] rather than re-fetched here — a
 * plain function over a `Boolean` is what makes this list buildable from a unit test with no Hilt
 * component and no second network call.
 */
internal fun visibleBookingsTabs(showConfirmedSegment: Boolean): List<BookingsTab> =
    buildList {
        add(BookingsTab.Pending)
        if (showConfirmedSegment) add(BookingsTab.Confirmed)
    }
