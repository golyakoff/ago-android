package ago.chat.android.bookings

/** Записи's own five sub-screens — Ожидают (`26-48`), Утверждены (`26-51`), Клиенты (`26-52`), Услуги
 * (`26-96`) и Часы (`26-97`). A plain UI-layer enum, not a `:core:domain` type: unlike
 * [ago.chat.android.core.domain.permissions.Permission], nothing outside this screen's own composables
 * and view models needs to know these names exist.
 *
 * `26-103`: this enum no longer means "one segment of the segmented control" for every member. Five
 * segments wrapped on a real device once `26-96`/`26-97` each added one (`docs/backlog/26-103-*.md`'s
 * own "Found"), so [visibleBookingsSegments] now draws only [Pending]/[Confirmed]/[Clients] as segments,
 * and [visibleBookingsConfigMenuEntries] offers [Services]/[Hours] from a `⋮` menu beside them instead
 * (`BookingsScreen`'s own `BookingsConfigMenu`). [BookingsTab] itself is unchanged — it is still just
 * "which body is selected" — only *how a member becomes selected* now differs by member.
 */
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
 *
 * `26-103`: this function used to also take `showServicesSegment`/`showHoursSegment` and add
 * [BookingsTab.Services]/[BookingsTab.Hours] as a fourth and fifth segment. That is what wrapped at five
 * segments on a real device (`docs/backlog/26-103-*.md`'s own "Found") — the approved fix is a
 * segmented control that never holds more than three, with the configuration pair reached from
 * [visibleBookingsConfigMenuEntries] instead, so those two booleans moved there and this function only
 * ever returns at most three entries now.
 */
internal fun visibleBookingsSegments(
    showConfirmedSegment: Boolean,
    showClientsSegment: Boolean,
): List<BookingsTab> =
    buildList {
        add(BookingsTab.Pending)
        if (showConfirmedSegment) add(BookingsTab.Confirmed)
        if (showClientsSegment) add(BookingsTab.Clients)
    }

/**
 * `26-103`: the `⋮` control's own entries — the configuration pair the segmented control used to carry
 * as its fourth and fifth segment. Both gates are unchanged from before this item, `calendar:configure`
 * alone, each independently computed and passed in exactly as [visibleBookingsSegments]'s own booleans
 * are — see [BookingsScreen]'s call site for why each stays its own parameter rather than one shared
 * flag (`26-96`/`26-97` each check the permission at their own endpoint, server-side).
 *
 * `26-96`: [showServicesSegment] is
 * [Permission.CALENDAR_CONFIGURE][ago.chat.android.core.domain.permissions.Permission.CALENDAR_CONFIGURE]
 * **alone** — a fourth independent gate, not [showClientsSegment] reused: that one is
 * `calendar:configure` *or* `customer:read`, and an operator holding only `customer:read` may read the
 * customer base without being allowed to rewrite the tenant's own service dictionary
 * (`ago-console`'s own `CalendarServicesPage.tsx` gates on `calendar:configure` alone, and the server
 * refuses anything else regardless).
 *
 * `26-97`: [showHoursSegment] is the identical gate, for the two working-hours endpoints
 * (`PUT`/`DELETE /working-hours/{ruleId}`) — kept as its own parameter rather than reusing
 * [showServicesSegment], since the two writes check the permission independently server-side, not
 * because either reuses the other's boolean.
 *
 * **"Hide, don't disable" one level up.** An empty result here is what makes `BookingsConfigMenu` draw
 * no `⋮` at all when neither entry applies (`docs/backlog/26-103-*.md`'s own Done-when) — the same rule
 * [ago.chat.android.analytics.AnalyticsReportsOverflowMenu] already follows for Аналитика's own `⋮`.
 */
internal fun visibleBookingsConfigMenuEntries(
    showServicesSegment: Boolean,
    showHoursSegment: Boolean,
): List<BookingsTab> =
    buildList {
        if (showServicesSegment) add(BookingsTab.Services)
        if (showHoursSegment) add(BookingsTab.Hours)
    }
