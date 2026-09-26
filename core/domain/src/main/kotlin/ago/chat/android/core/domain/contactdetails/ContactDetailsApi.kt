package ago.chat.android.core.domain.contactdetails

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-115`: the port the contact-detail panel's future view model reads through — declared here and
 * implemented in `:core:network` (`KtorContactDetailsApi`), the identical split
 * [ago.chat.android.core.domain.conversations.ConversationsApi] already establishes for the queue. The
 * dependency rule is what puts it here: a view model holding an `HttpClient` directly could not be
 * tested without one, and every HTTP-shaped decision (which status means what) belongs on the far side
 * of this interface, in the adapter.
 *
 * Confirmed against the real `ago-chat` code (`ContactDetailEndpoints.cs`,
 * `ListVisitorContactDetailsHandler`, `RevealVisitorContactDetailHandler`) rather than assumed from the
 * `26-111` design doc's own element map, per this item's own Scope. Two calls only — list and reveal —
 * matching `26-115`'s own scope: edit and the confirm/mark-invalid assessment exist on the server but
 * are out of scope here (the 26-111 "Author decisions" §1 drops name-assessment outright, and no ticket
 * has asked for edit yet).
 *
 * `26-167` (`docs/design/26-156-*.md` §6, ticket `26-171`): adds the two writes — [editContactDetail]
 * and [setContactDetailAssessment] — confirmed against the same `ago-chat` code at `8530e22`
 * (`EditVisitorContactDetailHandler`, `SetVisitorContactDetailAssessmentHandler`), both gated
 * `Permission.ConversationSend` server-side. Against **chat's own `VisitorContactDetail`s only** — never
 * the calendar `PersonRecord.Phone` (no edit API exists for that number, and must not: `PhoneVerifiedAt`
 * is bound to it) and never the dropped `customers` table (design doc §1).
 */
public interface ContactDetailsApi {
    /**
     * `GET /api/v1/conversations/{conversationId}/contact-details`, `RequireOperatorIdentity`-gated
     * server-side (`ContactDetailEndpoints.MapContactDetailEndpoints`). Every row this visitor has ever
     * had recorded, `Name` included — never filtered to only the masked ones, since the panel renders
     * `Name` as plain text beside the masked `Phone`/`Email` rows (26-111 decision #1).
     */
    public suspend fun fetchContactDetails(conversationId: String): ContactDetailsResult

    /**
     * `POST /api/v1/conversations/{conversationId}/contact-details/{contactDetailId}/reveal`,
     * operator-only, gated on `conversation:read` server-side
     * (`RevealVisitorContactDetailHandler.HandleAsync`) — a reveal is the same capability as reading the
     * conversation at all, not a stronger one layered on top.
     *
     * **No `surface` parameter travels on this call, unlike [26-53's calendar
     * reveal][ago.chat.android.core.domain.bookings.BookingsApi.revealCustomerPhone].** Confirming this
     * endpoint against `RevealVisitorContactDetailHandler` (`ago-chat`) found that the request carries no
     * body at all — the handler records every reveal under its own hardcoded
     * `ConsoleContactPanelSurface = "ConsoleContactPanel"` constant, regardless of caller. So an Android
     * reveal through this endpoint is recorded server-side as `"ConsoleContactPanel"` too, whatever this
     * app's own caller is — this port has no way to honor a distinct `"AndroidThread"` surface (as
     * `docs/backlog/26-115-*.md` asked for) until `ago-chat` accepts one, which is a backend change no
     * ticket has filed yet. Named here rather than silently worked around, per this codebase's own
     * instruction-source-boundary discipline (`docs/design/26-111-*.md` §0's identical move against a
     * mismatched mockup asset).
     *
     * Returns the server's own unmasked row — the same [ContactDetail] shape [fetchContactDetails]
     * already returns, with [ContactDetail.masked] now `false` — never a value this port unmasks itself.
     */
    public suspend fun revealContactDetail(
        conversationId: String,
        contactDetailId: String,
    ): RevealContactDetailResult

    /**
     * `PATCH /api/v1/conversations/{conversationId}/contact-details/{contactDetailId}` `{value}` —
     * operator-only, gated `conversation:send` (`EditVisitorContactDetailHandler`). An operator's own
     * correction to an existing row's own value, never a second, competing one — mirrors console's
     * «Изменить». **The server resets the row's `Assessment` back to `Unset` on a successful edit**
     * (`EditVisitorContactDetailHandler`'s own remarks) — the [ContactDetail] this returns already
     * reflects that, so a caller never needs to clear the assessment itself.
     *
     * Wrong-visitor and unknown-id both read as [ContactDetailWriteResult.Refused] with the server's
     * `VisitorContactDetail.NotFound` sentence — the identical info-hiding shape [revealContactDetail]
     * already documents. An empty or oversized [value] is refused the same way
     * (`VisitorContactDetail.Invalid`), never a client-side validation the port would have to keep in
     * sync with the domain's own rule.
     */
    public suspend fun editContactDetail(
        conversationId: String,
        contactDetailId: String,
        value: String,
    ): ContactDetailWriteResult

    /**
     * `PATCH /api/v1/conversations/{conversationId}/contact-details/{contactDetailId}/assessment`
     * `{assessment}` — operator-only, gated `conversation:send`
     * (`SetVisitorContactDetailAssessmentHandler`). [assessment] is
     * `Ago.Chat.Domain.VisitorContactDetailAssessment`'s own wire spelling, unparsed — `"Confirmed"` or
     * `"Invalid"` — the identical "the classification lives in `:core:domain`, the raw string travels"
     * discipline [ContactDetail.kind] already follows. **Never `"Unset"`**: the server refuses that as
     * not a settable target (there is no path back to unasserted through this endpoint — the same rule
     * the console follows), and a `Name` row is refused regardless of value
     * (`VisitorContactDetail.AssessmentNotApplicable`) — both surface as
     * [ContactDetailWriteResult.Refused], never a client-side pre-check duplicating the server's rule.
     */
    public suspend fun setContactDetailAssessment(
        conversationId: String,
        contactDetailId: String,
        assessment: String,
    ): ContactDetailWriteResult
}

/**
 * `Ago.Chat.Application.UseCases.ListVisitorContactDetails.VisitorContactDetailDto`, reduced to the
 * fields this panel renders — `recordedByOperatorId`/`source`/`verified`/`recordedAt` are on the wire and
 * simply omitted here, the same `ignoreUnknownKeys`-backed reduction
 * [ago.chat.android.core.domain.bookings.Contact]'s own doc comment explains for the calendar's contact
 * read: neither field has a screen yet.
 *
 * [kind] is `Ago.Chat.Domain.VisitorContactDetailKind`'s own wire spelling, unparsed —
 * `"Name"`/`"Phone"`/`"Email"` verbatim, the identical "the classification lives in `:core:domain`, the
 * raw string travels" discipline [ConversationSummary.state][ago.chat.android.core.domain.conversations.ConversationSummary]
 * already follows. **`Name` is never masked and never carries an invalid/unverified state** — trusted
 * and shown as written, per 26-111's own author decision.
 *
 * `26-167`: [assessment] joins the shape — `Ago.Chat.Domain.VisitorContactDetailAssessment`'s own wire
 * spelling, unparsed the identical way [kind] already is: `"Unset"`/`"Confirmed"`/`"Invalid"` verbatim,
 * default `"Unset"` for a row recorded before this field existed on this client (never `null` — the
 * server always sends it; the default only protects a stale cached shape). 26-111 decision #1 drops
 * *displaying* an assessment for a `Name` row; this port still carries whatever the server sent rather
 * than special-casing `kind` here, since the row's own kind already tells a caller whether to render it.
 */
public data class ContactDetail(
    val id: String,
    val kind: String,
    val value: String,
    /** `true` means [value] is a masked string and the caller may offer [ContactDetailsApi.revealContactDetail];
     * `false` means [value] already is the real one. Always `false` for a `kind == "Name"` row. */
    val masked: Boolean,
    val assessment: String = "Unset",
)

/** What reading one conversation's contact details came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.conversations.QueueResult] already establishes: every cause of "the
 * read failed" renders as the same one banner, so there is no reason to split this type further than
 * the value it carries. */
public sealed interface ContactDetailsResult {
    public data class Loaded(
        val details: List<ContactDetail>,
    ) : ContactDetailsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ContactDetailsResult
}

/** What asking to reveal one masked contact detail came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.conversations.ClaimResult] already establishes for a write that can be
 * genuinely refused. */
public sealed interface RevealContactDetailResult {
    /** A `2xx` carrying the server's own unmasked row. Named [contactDetail], not `detail` — this
     * sibling's own [Refused.detail] is an RFC 7807 sentence, a different thing entirely, and the two
     * must never be confused at a call site destructuring either arm. */
    public data class Revealed(
        val contactDetail: ContactDetail,
    ) : RevealContactDetailResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — shown to the operator verbatim; the
     * masked value stays on screen. */
    public data class Refused(
        val detail: String,
    ) : RevealContactDetailResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : RevealContactDetailResult
}

/**
 * `26-167`: what either write ([ContactDetailsApi.editContactDetail],
 * [ContactDetailsApi.setContactDetailAssessment]) came back with — the identical three-arm shape
 * [RevealContactDetailResult] already establishes above, generalised to "a write that can be genuinely
 * refused" rather than "a reveal" specifically: both PATCHes answer the same
 * `2xx`-row / non-2xx-`detail` / transport-failure split, so one result type serves both rather than two
 * parallel ones differing only in name.
 */
public sealed interface ContactDetailWriteResult {
    /** A `2xx` carrying the server's own updated row — the edit's post-edit value (assessment reset to
     * `Unset`, per [ContactDetailsApi.editContactDetail]'s own doc comment) or the assessment write's own
     * new [ContactDetail.assessment]. Named [contactDetail], not `detail` — the identical reason
     * [RevealContactDetailResult.Revealed] already gives for itself. */
    public data class Updated(
        val contactDetail: ContactDetail,
    ) : ContactDetailWriteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — a permission refusal, an unknown or
     * wrong-visitor id, an invalid value, or an inapplicable assessment all land here, shown to the
     * operator verbatim; nothing was written, and the draft on screen is kept. */
    public data class Refused(
        val detail: String,
    ) : ContactDetailWriteResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : ContactDetailWriteResult
}
