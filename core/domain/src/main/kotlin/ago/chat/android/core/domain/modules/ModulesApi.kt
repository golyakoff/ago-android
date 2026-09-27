package ago.chat.android.core.domain.modules

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-199`/`M1` (`docs/design/tenant-modules-restrictions-android.md` §2.1): the port behind
 * Ещё → Автоматизация → «База знаний»'s own read-only Модули panel — `GET
 * /api/v1/sites/{siteId}/modules`, the *only* verb this route still carries. Declared here and
 * implemented in `:core:network` (`KtorModulesApi`) — the identical dependency-rule split every other
 * `:app`-facing read in this codebase already draws ([ago.chat.android.core.domain.tags.SiteTagsApi]'s
 * own doc comment states it in full): a view model cannot hold an `HttpClient` directly without
 * becoming untestable, so every HTTP-shaped decision — which status means what, the `{siteId}` in the
 * path — stays on the far side of this interface.
 *
 * **Read-only by the platform's own design, not by this item's choice.** `adr/0151`/`23-83` removed
 * every tenant-facing module write (register, rotate, revoke, verify) — a tenant never turns a module on
 * for themselves; the platform owner does, or the system does on a payment. `Ago.Chat.Api`'s own
 * `ModuleEndpoints` maps only `MapGet` now, and `ago-console`'s `FaqModulePage` already renders this same
 * response read-only. This port therefore declares the one read and no write; inventing a client call
 * against a route the server does not expose would be worse than the honest gap this port states
 * (the design doc's own §5 note 1).
 */
public interface ModulesApi {
    /** `GET /api/v1/sites/{siteId}/modules`, `site:configure`-gated server-side. An empty list is a
     * genuine, drawable "nothing is on this account yet" state, never a failure — the identical
     * "empty is loaded" posture [ago.chat.android.core.domain.tags.SiteTagsApi.fetch]'s own doc comment
     * states for its sibling vocabulary read. */
    public suspend fun fetch(): ModulesResult
}

/**
 * `Ago.Chat.Api.Modules.ModuleEndpoints.EnableModuleResponse`, carried through unedited — this screen
 * renders the *whole* enabled-module list (a superset of what the console's own single-module-key panel
 * surfaces), so nothing here is reduced the way [ago.chat.android.core.domain.installation.SiteInstallation]
 * reduces its own larger wire response.
 */
public data class EnabledModule(
    /** e.g. `"faq"` — what a visitor types a trigger word toward. */
    val moduleKey: String,
    /** What a visitor types to start this module, in the server's own order. */
    val triggerWords: List<String>,
    /** The module's own service URL `Ago.Chat.Api` calls — shown, never dialled, by this screen. */
    val entryPoint: String,
    /** `true` when the platform owner enabled this module rather than the tenant's own operator
     * (`22-17`'s own audit distinction) — drawn as a badge, never a reason to hide the row. */
    val grantedByOwner: Boolean,
    /** `null` — a grant that does not expire. */
    val expiresAt: Instant?,
)

/** What reading the enabled-module list came back with — the identical two-arm "loaded, or a
 * classified failure" shape [ago.chat.android.core.domain.tags.TagVocabularyResult] already
 * establishes for its own sibling read. */
public sealed interface ModulesResult {
    public data class Loaded(
        val modules: List<EnabledModule>,
    ) : ModulesResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ModulesResult
}
