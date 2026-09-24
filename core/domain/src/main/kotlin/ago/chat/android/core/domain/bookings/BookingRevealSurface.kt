package ago.chat.android.core.domain.bookings

/**
 * `26-53`: which screen asked for a phone reveal, for [BookingsApi.revealCustomerPhone]'s own `surface`
 * argument — recorded verbatim on the server's own audit row (`ago-console`'s own `getPhoneReveals`,
 * read back on `/calendar/phone-reveals`) and read nowhere else; this app renders no audit trail of its
 * own (`docs/backlog/26-53-*.md`'s own Out of scope: "reading the audit trail on Android... belongs
 * with [Аналитика]").
 *
 * **Android's own constants, never a borrowed console string.** `ago-console`'s screens pass
 * `"ConsoleQueue"`/`"ConsoleContacts"` (`CalendarQueuePage.tsx:200`/`CalendarContactsPage.tsx:126`) —
 * reusing either of those from this app would silently corrupt the one field the audit trail exists to
 * answer ("which client actually revealed this"), collapsing a phone call's worth of client-side
 * evidence into "some console screen did it". [ANDROID_CONTACTS] is this app's own first, real caller
 * (Клиенты, wired the moment this item lands); [ANDROID_QUEUE] is named now, alongside it, for the
 * pending-queue card's own reveal control this exact same audit-trail argument will need the day that
 * control exists — `docs/backlog/26-53-*.md`'s own Scope item 2 asks for both names up front specifically
 * so nothing later has to invent a matching one under time pressure. It is not called from anywhere yet:
 * `26-49` (this app's pending-queue veto actions) drew no reveal control of its own, on purpose, and
 * naming a constant is not the same claim as wiring a caller to it.
 */
public object BookingRevealSurface {
    /** `26-53`: Клиенты's own reveal — the only caller this item actually wires. */
    public const val ANDROID_CONTACTS: String = "AndroidContacts"

    /** Reserved for the pending-queue card's own reveal control, once one exists — unused by any call
     * site today. */
    public const val ANDROID_QUEUE: String = "AndroidQueue"
}
