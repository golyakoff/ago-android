package ago.chat.android.core.domain.ai

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-246` (`ago-console`'s own `AiReplyDraftPage`/`aiAddOnApi.ts`): the port behind Автоматизация →
 * «ИИ-подсказки» — `GET /api/v1/sites/{siteId}/ai-add-on`, plus `POST .../enable` and `POST .../disable`,
 * every route gated `site:configure` *inside the handler*, the same permission the row itself is gated on
 * — the identical "no rail-vs-server gap" shape
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi] already establishes for its own
 * site-scoped settings screen. Declared here, implemented in `:core:network` (`KtorAiReplyDraftApi`) — a
 * view model holding an `HttpClient` directly could not be tested without one, and every HTTP-shaped
 * decision belongs on the far side of this interface, in the adapter.
 *
 * **One switch, two doors.** `AiAddOnEnablement` (ago-chat) is a single per-site flag that both the
 * operator-facing reply draft *and* the background conversation categoriser consult — the console's own
 * `AiReplyDraftPage` and `AiAddOnPage` are two screens onto that one switch, not two switches. This app
 * builds only the reply-draft door (`AiAddOnPage`'s own buy-the-add-on/accept-terms flow, `25-04`, is not
 * built here), so [enable]/[disable] here are the exact same `enable`/`disable` the console calls.
 *
 * **Never an optimistic toggle.** The view model re-reads [fetch] after every [enable]/[disable] and
 * renders from whatever the server returns rather than flipping local state on click — the identical
 * discipline `AiReplyDraftPage`'s own `run` holds, and the reason a failed enable never leaves the switch
 * showing "on".
 */
public interface AiReplyDraftApi {
    /** `GET /api/v1/sites/{siteId}/ai-add-on`. */
    public suspend fun fetch(): AiReplyDraftStatusResult

    /** `POST /api/v1/sites/{siteId}/ai-add-on/enable` when [enabled] is `true`, `.../disable` otherwise. */
    public suspend fun setEnabled(enabled: Boolean): AiReplyDraftWriteResult
}

/**
 * The reply-draft switch's own availability and position, distilled from `AiAddOnStatusResponse`
 * (`ago-chat`). The wire carries the two independent legal pairs (`acceptedVersion`/`acceptedAt`,
 * `declaredBy`/`declaredAt`) that `AiAddOnPage` records; this door only needs to know *whether* each was
 * made, so the adapter collapses each pair to a boolean — the very distinction the console keeps in full
 * (accepting AGO's terms vs declaring a lawful basis) is not editable here, only reported as
 * [setupComplete] or not.
 *
 * @param purchased whether the account holds the AI add-on at all — `false` is the "not available yet"
 *   state the console shows a "contact AGO" note for.
 * @param enabled the switch's current position — the one thing this screen turns on and off.
 * @param effectiveFrom ISO-8601 instant, or `null` — only conversations created from this moment on are
 *   sent to the provider; shown only while [enabled].
 * @param agreementAccepted whether the AI-features agreement has been accepted (`acceptedVersion` present).
 * @param basisDeclared whether the lawful-basis declaration has been made (`declaredAt` present).
 */
public data class AiReplyDraftStatus(
    val purchased: Boolean,
    val enabled: Boolean,
    val effectiveFrom: String?,
    val agreementAccepted: Boolean,
    val basisDeclared: Boolean,
) {
    /** The switch can be enabled only once the add-on is bought and both legal statements are on record —
     * the console's own `canEnable = purchased && accepted && declared`. Until then this door shows a
     * read-only "finish setup in the console" note rather than an enable button that would only be
     * refused. */
    public val setupComplete: Boolean get() = purchased && agreementAccepted && basisDeclared
}

/** What reading the reply-draft status came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyResult] already establishes for a site-scoped
 * settings read. */
public sealed interface AiReplyDraftStatusResult {
    public data class Loaded(
        val status: AiReplyDraftStatus,
    ) : AiReplyDraftStatusResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : AiReplyDraftStatusResult
}

/** What toggling the switch came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult] establishes, minus its own echo:
 * `enable`/`disable` return no body, so [Saved] carries nothing and the view model re-reads [fetch] to
 * learn the new position (`AiReplyDraftPage`'s own reload-after-write discipline). */
public sealed interface AiReplyDraftWriteResult {
    /** A `2xx`. The write landed; the view model re-reads the status rather than trusting a local flip. */
    public data object Saved : AiReplyDraftWriteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (e.g. `AiAddOn.AgreementNotAccepted`),
     * shown to the operator verbatim; nothing changed. */
    public data class Refused(
        val detail: String,
    ) : AiReplyDraftWriteResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : AiReplyDraftWriteResult
}
