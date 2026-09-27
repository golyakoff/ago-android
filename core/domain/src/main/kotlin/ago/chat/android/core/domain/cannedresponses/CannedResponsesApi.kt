package ago.chat.android.core.domain.cannedresponses

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1): the port behind Автоматизация → «Готовые
 * ответы» — `GET`/`PUT /api/v1/sites/{siteId}/canned-responses`, `site:configure` on both verbs *inside
 * the handler*, the same permission the row itself is gated on — the identical "no rail-vs-server gap"
 * shape [ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi] already establishes for its own
 * site-scoped settings form. Declared here, implemented in `:core:network` (`KtorCannedResponsesApi`) —
 * the dependency rule is what puts it here rather than beside the Ktor client: a view model holding an
 * `HttpClient` directly could not be tested without one, and every HTTP-shaped decision (which status
 * means what, which base URL, which `{siteId}`) belongs on the far side of this interface, in the
 * adapter.
 *
 * **[save] always carries the whole library, never a subset.** There is no per-item endpoint — the
 * console's own `CannedResponse` doc comment states the list is a JSON blob column on `Site`, read and
 * written as a whole, unlike `Tag`, which is a real table. A response carries no `id`; its identity is
 * only its position in the list. This is the identical whole-DTO discipline
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi.update]'s own doc comment states for its
 * own rule list, restated here for a stronger reason: that port's rows at least have order-as-behaviour
 * to justify a full resend, this one has no id at all, so there is no partial-update shape even
 * conceivable.
 */
public interface CannedResponsesApi {
    /**
     * `GET /api/v1/sites/{siteId}/canned-responses`. An empty list is a *loaded* empty library — a valid,
     * expected state — never a failure.
     */
    public suspend fun fetch(): CannedResponsesResult

    /** `PUT /api/v1/sites/{siteId}/canned-responses`, body = the complete [responses], in the exact order
     * given. */
    public suspend fun save(responses: List<CannedResponse>): CannedResponsesWriteResult
}

/**
 * One prepared answer an operator inserts into the composer verbatim — a short [title] to browse by and
 * a [body] that is never matched against a visitor's message, unlike an auto-reply keyword
 * (`CannedResponse`'s own doc comment, `ago-chat`). No `id` field: a response's identity is its position
 * in the enclosing [CannedResponsesResult.Loaded.responses]/[CannedResponsesWriteResult.Saved.responses]
 * list, never a value carried on the type itself.
 */
public data class CannedResponse(
    val title: String,
    val body: String,
)

/**
 * `Ago.Chat.Domain.CannedResponse`/`cannedResponsesValidation.ts`'s own bounds, restated here as the one
 * place both the adapter's tests and `:app`'s client-side courtesy check
 * (`ago.chat.android.automation.validateCannedResponsesDraft`) read them from, rather than each side
 * typing its own copy of `50`/`100`/`8000` — the identical "one source, several readers" reasoning
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyBounds] already follows for a bound this port's
 * own domain type carries.
 */
public object CannedResponseBounds {
    /** Mirrors `Ago.Chat.Domain.CannedResponse.MaxCount`/`cannedResponsesValidation.ts`'s own
     * `MAX_COUNT`. */
    public const val MAX_COUNT: Int = 50

    /** Mirrors `Ago.Chat.Domain.CannedResponse.MaxTitleLength`/`cannedResponsesValidation.ts`'s own
     * `MAX_TITLE_LENGTH`. Title is trimmed, client and server. */
    public const val MAX_TITLE_LENGTH: Int = 100

    /** Mirrors `MessageBody.MaxLength` — a canned response's body becomes a message body verbatim, so it
     * shares that bound rather than carrying its own. Body is stored as typed, never trimmed. */
    public const val MAX_BODY_LENGTH: Int = 8000
}

/** What reading the canned-response library came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyResult] already establishes for a site-scoped
 * settings read. */
public sealed interface CannedResponsesResult {
    public data class Loaded(
        val responses: List<CannedResponse>,
    ) : CannedResponsesResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : CannedResponsesResult
}

/** What saving the whole library came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult] already establishes for a write
 * that can be genuinely refused (`CannedResponse.Invalid`, if the client's own courtesy check ever misses
 * something the server still catches). */
public sealed interface CannedResponsesWriteResult {
    /** A `2xx` carrying the server's own echo of the saved library. The view model re-seeds its list from
     * *this*, never from the [CannedResponse]s it sent — the identical reload-after-write discipline
     * [ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult.Saved]'s own doc comment
     * states, and the one property that makes the console's own last-write-wins blob safe to look at
     * after a concurrent edit. */
    public data class Saved(
        val responses: List<CannedResponse>,
    ) : CannedResponsesWriteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (`CannedResponse.Invalid`), shown to the
     * operator verbatim; nothing was written, and the draft on screen is kept. */
    public data class Refused(
        val detail: String,
    ) : CannedResponsesWriteResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : CannedResponsesWriteResult
}
