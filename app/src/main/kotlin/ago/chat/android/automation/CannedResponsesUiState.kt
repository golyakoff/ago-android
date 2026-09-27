package ago.chat.android.automation

import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1.4): [CannedResponsesViewModel]'s own state —
 * the identical loading/failed/loaded shape this app's other read-then-edit screens already establish
 * ([ago.chat.android.automation.OfflineAutoReplyUiState]), sized for a whole-list PUT rather than that
 * screen's own ordered-rule draft: there is no separate "draft" concept here at all, [Loaded.responses]
 * *is* what the next [CannedResponsesViewModel.save] call sends, in full, every time.
 *
 * **The editor (a new entry, or an existing index) is not a state of its own.** Which one, if any, is
 * open lives as transient composition state in [CannedResponsesScreen], the identical discipline
 * [ago.chat.android.schedule.WorkingHoursScreen]'s own edit dialog already follows: "a property of this
 * composition, not a fact the view model or a process-death restore has any business carrying."
 */
internal sealed interface CannedResponsesUiState {
    data object Loading : CannedResponsesUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : CannedResponsesUiState

    /**
     * @param responses the whole in-memory library, in server order — exactly the payload the next
     *   [ago.chat.android.core.domain.cannedresponses.CannedResponsesApi.save] call sends, since there is
     *   no per-item endpoint to send anything smaller to.
     * @param saving `true` while a whole-list `PUT` is in flight, triggered by an add, an edit or a
     *   delete alike — they all reduce to the same one write.
     * @param savedTick bumped on every successful save — the identical
     *   [ago.chat.android.channels.WidgetConfigUiState.Loaded.savedTick] shape, driving a "Сохранено"
     *   snackbar even across two consecutive identical saves, and the signal
     *   [CannedResponsesScreen] uses to close the editor only on a genuine success.
     * @param error the most recent write's own outcome — a local courtesy-validation problem caught
     *   before any network call, the server's own refusal, or a transport failure. `null` once a new
     *   write starts or one succeeds.
     */
    data class Loaded(
        val responses: List<CannedResponse>,
        val saving: Boolean = false,
        val savedTick: Int = 0,
        val error: CannedResponsesActionError? = null,
    ) : CannedResponsesUiState
}

/**
 * A write attempt's own three honest outcomes — the identical split
 * [ago.chat.android.automation.OfflineAutoReplyActionError] already establishes for the auto-reply save,
 * restated here rather than reused since neither port is the other's concern.
 */
internal sealed interface CannedResponsesActionError {
    /** The client-side courtesy check's own verdict (`docs/design/tenant-canned-tags-android.md` §1.5),
     * caught before the network is ever touched — the server, not this arm, remains the authoritative
     * validator ([ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult.Refused] is
     * what a validation gap the courtesy check missed comes back as). */
    data class Invalid(
        val problem: CannedResponseValidationProblem,
    ) : CannedResponsesActionError

    /** The server's own validation sentence (`CannedResponse.Invalid`), shown verbatim. */
    data class ServerRefusal(
        val detail: String,
    ) : CannedResponsesActionError

    /** A dropped connection, or a non-2xx with no `detail` to show — rendered through
     * [ago.chat.android.ui.components.networkFailureText]. */
    data class Unavailable(
        val reason: NetworkFailure,
    ) : CannedResponsesActionError
}
