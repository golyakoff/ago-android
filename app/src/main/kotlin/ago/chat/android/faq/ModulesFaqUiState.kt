package ago.chat.android.faq

import ago.chat.android.core.domain.modules.EnabledModule
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-199`/`M1` (`docs/design/tenant-modules-restrictions-android.md` §2.1, §2.3): Ещё → Автоматизация →
 * «База знаний»'s own read-only Модули panel. The identical three-arm Loading/Loaded/Failed vocabulary
 * every other single-read screen in this app already uses
 * ([ago.chat.android.automation.TagsUiState.Loaded] and
 * [ago.chat.android.channels.InstallWidgetUiState] are the closest sibling shapes: a
 * `site:configure`-gated, no-mutation-of-its-own read that renders a list or an "ask us" empty note).
 *
 * **Not [ago.chat.android.faq.ModulesFaqUiState] wrapping two independent sub-states yet.** The design
 * doc's own §2.3 sketches one view model eventually holding a `modules` state *and* a `kb` state, since
 * `M2` (a separate, dependent backlog item) adds a second panel — the FAQ knowledge base — to this same
 * screen, reading a different backend. `M1`'s own promise is the Модули panel alone (rule 15: "one
 * ticket, one thing"), so this state is the plain top-level sealed type every other single-panel read
 * screen already is; restructuring it into a two-sub-state holder is `M2`'s own job when it adds the
 * second panel, not speculative scaffolding built ahead of the ticket that needs it.
 */
public sealed interface ModulesFaqUiState {
    public data object Loading : ModulesFaqUiState

    /** [modules] in the server's own order. An empty list is the genuine "nothing is on this account yet"
     * state (`Ago.Chat.Api`'s own `ModuleEndpoints` — a tenant never turns a module on for itself,
     * `adr/0151`) — rendered as the "ask us" empty note, never a spinner or an error. */
    public data class Loaded(
        val modules: List<EnabledModule>,
    ) : ModulesFaqUiState

    public data class Failed(
        val reason: NetworkFailure,
    ) : ModulesFaqUiState
}
