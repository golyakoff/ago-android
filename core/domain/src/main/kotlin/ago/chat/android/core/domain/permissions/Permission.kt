package ago.chat.android.core.domain.permissions

/**
 * `26-16`: the permission vocabulary, arriving in `:core:domain` for its first consumer — the same
 * rule `0-01` applied on the platform side (`ago-root/CLAUDE.md` rule 2: a port belongs where its
 * first real caller needs it, not before). Every name below is copied verbatim from `ago-chat`'s own
 * `Ago.Chat.Domain.Permission` (`ago-root/docs/architecture/authorization.md`) and from
 * `ago-console/src/shell/consoleNav.ts`'s own gate list — this app invents no permission of its own,
 * because hiding a control here is UX only; the server's `IPermissionChecker` is what actually
 * refuses (`navigation.md` §"The top-level shape").
 *
 * Deliberately a flat object of `String` constants, not an `enum class`: the wire format is a string
 * (`OperatorPermissionsResponse.permissions: string[]`), and a permission this app has not yet named
 * still round-trips through [OperatorPermissions] correctly — an enum would either drop an unknown
 * name silently or force a `when` to handle a case Android does not know about yet. The console makes
 * the identical choice (`hasPermission(permission: string)`, never a union type).
 */
public object Permission {
    /** Gates the whole Записи destination, and is also what makes an operator "the tenant" elsewhere
     * in this codebase (`authorization.md`: the `site:configure`-as-tenant-proxy reasoning). */
    public const val CALENDAR_CONFIGURE: String = "calendar:configure"

    /** One of three booking-action permissions that, on their own, also earn Записи a place in the
     * bar — `navigation.md`'s own top-level table: "`calendar:configure`, or any booking action
     * permission, or `customer:read`". */
    public const val BOOKING_CONFIRM: String = "booking:confirm"
    public const val BOOKING_REJECT: String = "booking:reject"
    public const val BOOKING_CANCEL: String = "booking:cancel"

    /** The fourth, independent path to the same destination. */
    public const val CUSTOMER_READ: String = "customer:read"

    /** Gates the roster row inside Команда specifically — the destination itself is always drawn,
     * since "Общение" is ungated (`navigation.md` §"Команда, Аналитика"). Not consumed by this item's
     * own hide-when-empty computation (Команда never collapses), but named here now so the item that
     * builds Команда's real content has no second place to look for the string. */
    public const val SITE_MANAGE_OPERATORS: String = "site:manage_operators"

    /** The console's own proxy for "this identity is the tenant" (`consoleNav.ts`'s own `isAdmin`).
     * Named here for the same forward-looking reason as [SITE_MANAGE_OPERATORS] — Аналитика's admin
     * items and Ещё's Администрирование section both gate on it once their real screens exist. */
    public const val SITE_CONFIGURE: String = "site:configure"

    /** `26-85`: "has a conversation to be pushed about" — the fact `OperatorPresenceController`
     * (`:app`) gates [ago.chat.android.presence.OperatorPresenceService] on, copied verbatim from
     * `ago-chat`'s own `Ago.Chat.Domain.Permission.ConversationSend` (`docs/backlog/26-85-*.md`).
     * Distinct from `ConversationAssign` (claiming a conversation from the queue) — an identity holding
     * neither is a pure administrator with no operator seat and no conversation this service could ever
     * be woken up about, so it is this permission, not merely "signed in", that decides whether the
     * foreground service ever starts. Not consumed by [OperatorPermissions.holds] anywhere in
     * `:core:domain` itself (no navigation destination gates on it), which is why it was not already
     * named here before this item needed it. */
    public const val CONVERSATION_SEND: String = "conversation:send"

    /** `26-90`: "may permanently erase one conversation" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationErase`. Bundled into the administrator set by
     * `RegisterSiteHandler`/`MintDemoTenantHandler` alongside `site:erase`/`site:export` and never
     * granted to an operator separately (`26-90`'s own Out of scope), so in practice it travels with
     * [SITE_CONFIGURE] — but it is checked on its own here rather than folded into that one, because
     * "may see every conversation on the site" and "may destroy one" are two different capabilities
     * and the server checks them separately too. Gates the swipe-to-erase action on the «Все» tab, and
     * nothing else. */
    public const val CONVERSATION_ERASE: String = "conversation:erase"

    /** `26-116`: "may read this conversation's own detail" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationRead`. Gates the contact-detail panel's own existence
     * (`docs/design/26-111-thread-contact-detail-panel.md`'s Appendix: "See the sheet / read
     * contact-details / reveal / read tags / read notes / read history") — an operator without it never
     * sees the panel's info affordance at all, hide-not-disable like every other permission here. Not
     * [CONVERSATION_SEND]: reading a conversation's contact details, tags and notes is a weaker claim
     * than being allowed to act in it, and the server checks the two separately. */
    public const val CONVERSATION_READ: String = "conversation:read"

    /** `26-116`: "may apply or remove a tag" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationTag`. Gates the panel's «+ метка» affordance and each
     * chip's own remove (`26-111`'s design, T2). */
    public const val CONVERSATION_TAG: String = "conversation:tag"

    /** `26-116`: "may add a team note" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationNoteWrite`. Gates only the notes composer; the notes
     * *count* row itself is read-gated by [CONVERSATION_READ] (`26-111`'s design, N1). */
    public const val CONVERSATION_NOTE_WRITE: String = "conversation:note_write"

    /** `26-116`: "may close this conversation" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationClose`. Gates the panel's «Закрыть диалог» action
     * (`26-111`'s design, A1). */
    public const val CONVERSATION_CLOSE: String = "conversation:close"

    /** `26-116`: "may block a visitor site-wide" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationBlock`. Gates the panel's reversible «Ограничить» /
     * «Снять ограничение» action (`26-111`'s design, A2, Author decision #2: one reversible action, no
     * second destructive verb — so [CONVERSATION_MARK_SPAM] below is named for completeness but this is
     * the one the panel actually gates on). */
    public const val CONVERSATION_BLOCK: String = "conversation:block"

    /** `26-116`: "may close a conversation as spam" — copied verbatim from `ago-chat`'s own
     * `Ago.Chat.Domain.Permission.ConversationMarkSpam`. Part of the same restrict-visitor gate family
     * as [CONVERSATION_BLOCK] in `26-111`'s own Appendix table; named here now so the write-actions
     * item (`26-120`) has no second place to look for the string, even though the panel's Author
     * decision #2 settled on [CONVERSATION_BLOCK] alone as the one action actually wired up. */
    public const val CONVERSATION_MARK_SPAM: String = "conversation:mark_spam"

    /** `26-116`: "may grant or revoke a visitor's attachment-upload permission" — copied verbatim from
     * `ago-chat`'s own `Ago.Chat.Domain.Permission.ConversationAttachmentUploadGrant`. Gates the panel's
     * «Приём файлов от посетителя» toggle (`26-111`'s design, F1). */
    public const val CONVERSATION_ATTACHMENT_UPLOAD_GRANT: String = "conversation:attachment_upload_grant"
}

/** Every permission [Permission.CALENDAR_CONFIGURE]'s own destination can be earned through, per
 * `navigation.md`'s top-level table — factored out so [ago.chat.android.core.domain.navigation.canSeeBookings]
 * reads as one call rather than four `holds` checks inlined at its own call site. */
internal val bookingsGate: List<String> =
    listOf(
        Permission.CALENDAR_CONFIGURE,
        Permission.BOOKING_CONFIRM,
        Permission.BOOKING_REJECT,
        Permission.BOOKING_CANCEL,
        Permission.CUSTOMER_READ,
    )
