package ago.chat.android.automation

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.3): [OfflineAutoReplyViewModel]'s own
 * state — the identical loading/failed/loaded shape this app's other read-then-edit screens already
 * establish (`ago.chat.android.channels.WidgetConfigUiState`), sized for one full-DTO save rather than
 * [ago.chat.android.channels.BrandingUiState]'s own two independent writes: there is exactly one
 * [OfflineAutoReplyApi][ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi.update] call this
 * screen ever makes, carrying the whole draft at once.
 */
internal sealed interface OfflineAutoReplyUiState {
    data object Loading : OfflineAutoReplyUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : OfflineAutoReplyUiState

    /**
     * @param enabled the draft's own switch position - starts at the server's last-saved value, edited
     *   in place, never auto-saved.
     * @param fallbackReply the draft's own default-reply text.
     * @param rules the draft's own ordered rule list, **in the order the operator arranged it** - that
     *   order is sent to the server verbatim on [OfflineAutoReplyViewModel.save] (blank rows dropped) and
     *   is itself the first-match-wins behaviour, never re-sorted by this screen.
     * @param saving `true` while a save is in flight - [OfflineAutoReplyViewModel.save] is a no-op while
     *   this is already `true`, the identical "no-op while its own kind is already in flight" discipline
     *   [ago.chat.android.channels.ChannelConnectViewModel]'s own doc comment states for its own
     *   connect/disconnect pair.
     * @param savedTick bumped on every successful save - the identical
     *   [ago.chat.android.channels.WidgetConfigUiState.Loaded.savedTick] shape, driving a "Сохранено"
     *   snackbar even across two consecutive identical saves.
     * @param saveError the most recent save attempt's own outcome - a local courtesy-validation problem
     *   caught before any network call, the server's own refusal, or a transport failure. `null` once a
     *   new save starts or one succeeds.
     */
    data class Loaded(
        val enabled: Boolean,
        val fallbackReply: String,
        val rules: List<AutoReplyRuleDraft>,
        val saving: Boolean = false,
        val savedTick: Int = 0,
        val saveError: OfflineAutoReplyActionError? = null,
    ) : OfflineAutoReplyUiState
}

/**
 * One rule row in the editor's own draft list. [id] is this screen's own local identity, never sent to
 * the server (the wire shape carries only `keyword`/`reply`, `docs/design/tenant-channels-android.md`
 * §4.1) - it exists purely so Compose's `key(rule.id)` (`OfflineAutoReplyScreen`'s own reorderable list)
 * and this view model's own per-row edit/remove calls can address a row that survives a drag-reorder
 * without depending on its on-screen index, which changes on every reorder.
 */
internal data class AutoReplyRuleDraft(
    val id: Long,
    val keyword: String,
    val reply: String,
)

/**
 * `26-192`: a save attempt's own three honest outcomes - the identical split
 * [ago.chat.android.channels.BrandingActionError] already establishes for a branding write, plus one
 * arm neither branding write needs: [Invalid], the client-side courtesy check's own verdict
 * (`docs/design/tenant-channels-android.md` §4.3), caught by [OfflineAutoReplyViewModel.save] *before*
 * the network is ever touched - the server, not this arm, remains the authoritative validator
 * ([ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult.Refused] is what a validation gap
 * the courtesy check missed comes back as).
 */
internal sealed interface OfflineAutoReplyActionError {
    data class Invalid(
        val problem: OfflineAutoReplyValidationProblem,
    ) : OfflineAutoReplyActionError

    data class ServerRefusal(
        val detail: String,
    ) : OfflineAutoReplyActionError

    data class Unavailable(
        val reason: NetworkFailure,
    ) : OfflineAutoReplyActionError
}
