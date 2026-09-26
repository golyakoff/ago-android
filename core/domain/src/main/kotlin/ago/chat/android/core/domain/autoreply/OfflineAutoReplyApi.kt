package ago.chat.android.core.domain.autoreply

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.1/§4.2): the port behind Автоматизация →
 * «Автоответ вне смены» — `GET`/`PUT /api/v1/sites/{siteId}/offline-auto-reply`, gated `site:configure`
 * on both verbs *inside the handler*, the same permission the row itself is gated on — the identical
 * "no rail-vs-server gap" shape [ago.chat.android.core.domain.widgetconfig.WidgetConfigApi] already
 * establishes for its own site-scoped settings form. Declared here, implemented in `:core:network`
 * (`KtorOfflineAutoReplyApi`) — a view model holding an `HttpClient` directly could not be tested
 * without one, and every HTTP-shaped decision belongs on the far side of this interface, in the adapter.
 *
 * **[update] always carries the whole [OfflineAutoReply], never a subset** — the identical full-DTO
 * discipline [WidgetConfigApi]'s own doc comment states for its own `update`, restated here because the
 * server matches keyword rules first-rule-wins: [OfflineAutoReply.rules]' own list order *is* the
 * behaviour, so there is no partial-update shape that could not silently drop or reorder a rule the
 * server never saw change.
 */
public interface OfflineAutoReplyApi {
    /** `GET /api/v1/sites/{siteId}/offline-auto-reply`. */
    public suspend fun fetch(): OfflineAutoReplyResult

    /** `PUT /api/v1/sites/{siteId}/offline-auto-reply`, body = the complete [settings]. */
    public suspend fun update(settings: OfflineAutoReply): OfflineAutoReplyWriteResult
}

/**
 * One keyword rule: the first rule (in [OfflineAutoReply.rules] list order) whose [keyword] matches a
 * visitor's message wins — the server's own first-match-wins semantics
 * (`docs/design/tenant-channels-android.md` §4). [OfflineAutoReplyBounds] carries [keyword]'s and
 * [reply]'s own length ceilings; this type itself never enforces them — the client-side courtesy check
 * that does lives in `:app` (`ago.chat.android.automation.validateOfflineAutoReplyDraft`), the identical
 * "the port carries the shape, the app decides what is worth stopping before a round trip" split every
 * courtesy check in this codebase already follows (`docs/design/tenant-channels-android.md` §3.4's own
 * branding-logo check is the other example, kept in `:app` for its own, unrelated reason — it calls
 * Android's `BitmapFactory`).
 */
public data class AutoReplyRule(
    val keyword: String,
    val reply: String,
)

/**
 * `Ago.Chat.Domain.OfflineAutoReplySettings`/`OfflineAutoReplyRule`'s own bounds, restated here as the
 * one place both the adapter's tests and `:app`'s client-side courtesy check
 * (`ago.chat.android.automation.validateOfflineAutoReplyDraft`) read them from, rather than each side
 * typing its own copy of `20`/`64`/`1000` — the same "one source, several readers" reasoning
 * [ago.chat.android.core.domain.widgetconfig.WidgetAutoOpenDelay]'s own companion constants already
 * follow for a bound this port's own domain type carries.
 */
public object OfflineAutoReplyBounds {
    /** Mirrors `Ago.Chat.Domain.OfflineAutoReplySettings.MaxRules` / `offlineAutoReplyValidation.ts`'s
     * own `MAX_RULES`. */
    public const val MAX_RULES: Int = 20

    /** Mirrors `Ago.Chat.Domain.OfflineAutoReplyRule.MaxKeywordLength` / `offlineAutoReplyValidation.ts`'s
     * own `MAX_KEYWORD_LENGTH`. */
    public const val MAX_KEYWORD_LENGTH: Int = 64

    /** Mirrors `Ago.Chat.Domain.OfflineAutoReplyRule.MaxReplyLength` / `offlineAutoReplyValidation.ts`'s
     * own `MAX_REPLY_LENGTH` — also bounds [OfflineAutoReply.fallbackReply]. */
    public const val MAX_REPLY_LENGTH: Int = 1000
}

/**
 * The whole `OfflineAutoReplySettingsDto`/update-request shape, field for field
 * (`docs/design/tenant-channels-android.md` §4.1) — [rules] is **ordered**, and that order is the
 * behaviour a save must preserve exactly as the operator arranged it, never re-sorted or deduplicated on
 * this side of the port.
 */
public data class OfflineAutoReply(
    val enabled: Boolean,
    val fallbackReply: String,
    val rules: List<AutoReplyRule>,
)

/** What reading the offline auto-reply settings came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.widgetconfig.WidgetConfigResult] already establishes for a site-scoped
 * settings read. */
public sealed interface OfflineAutoReplyResult {
    public data class Loaded(
        val settings: OfflineAutoReply,
    ) : OfflineAutoReplyResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : OfflineAutoReplyResult
}

/** What saving the offline auto-reply settings came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.widgetconfig.WidgetConfigWriteResult] already establishes for a write
 * that can be genuinely refused (`OfflineAutoReply.Invalid`, here, if the client's own courtesy check
 * ever misses something the server still catches). */
public sealed interface OfflineAutoReplyWriteResult {
    /** A `2xx` carrying the server's own echo of the saved settings. The view model re-seeds its
     * committed draft from *this*, never from the [OfflineAutoReply] it sent — the identical
     * reload-after-write discipline [WidgetConfigWriteResult.Saved]'s own doc comment states. */
    public data class Saved(
        val settings: OfflineAutoReply,
    ) : OfflineAutoReplyWriteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail`, shown to the operator verbatim; nothing
     * was written, and the draft on screen is kept. */
    public data class Refused(
        val detail: String,
    ) : OfflineAutoReplyWriteResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : OfflineAutoReplyWriteResult
}
