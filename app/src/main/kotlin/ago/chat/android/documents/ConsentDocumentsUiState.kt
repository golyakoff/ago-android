package ago.chat.android.documents

import ago.chat.android.core.domain.consent.ConsentAcceptance
import ago.chat.android.core.domain.consent.ConsentOverview
import ago.chat.android.core.domain.consent.ConsentPurpose
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §3.1): [ConsentDocumentsViewModel]'s own state —
 * the identical loading/failed/loaded shape this app's other read-then-edit screens already establish
 * ([ago.chat.android.automation.CannedResponsesUiState]).
 *
 * **Which purpose's publish editor is open is not a state of its own.** It lives as transient
 * composition state in [ConsentDocumentsScreen], the identical discipline
 * [ago.chat.android.automation.CannedResponsesScreen]'s own `editing` target already follows — a
 * property of this composition, never a fact a process-death restore has any business carrying.
 */
internal sealed interface ConsentDocumentsUiState {
    data object Loading : ConsentDocumentsUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : ConsentDocumentsUiState

    /**
     * @param overview the whole two-purpose read.
     * @param acceptances every "кто принял" expander's own state, keyed by [AcceptanceKey] so two
     *   versions - even two versions of the *same* purpose - never share or merge a list (the `25-21`
     *   crux `tenant-consent-android.md` §3.2 restates). A key absent from this map has never been
     *   expanded; fetched lazily on first expand, cached here for the life of this [Loaded] value so a
     *   second expand of the same version does not repeat the network call.
     * @param publishing `true` while a publish write is in flight for whichever purpose the editor
     *   currently targets - there is only ever one editor open at a time (transient composition
     *   state), so one flag is enough, the identical single-flag shape
     *   [ago.chat.android.automation.CannedResponsesUiState.Loaded.saving] already establishes.
     * @param publishError the most recent publish attempt's own outcome - `null` once a new attempt
     *   starts or one succeeds.
     * @param savedTick bumped on every successful publish - the identical
     *   [ago.chat.android.automation.CannedResponsesUiState.Loaded.savedTick] shape, driving a
     *   "Версия опубликована" snackbar and the signal [ConsentDocumentsScreen] uses to close the editor
     *   only on a genuine success.
     */
    data class Loaded(
        val overview: ConsentOverview,
        val acceptances: Map<AcceptanceKey, AcceptancesUiState> = emptyMap(),
        val publishing: Boolean = false,
        val publishError: ConsentPublishActionError? = null,
        val savedTick: Int = 0,
    ) : ConsentDocumentsUiState
}

/** One "кто принял" expander's own identity - a version is only unique within its own purpose, so both
 * are needed. */
internal data class AcceptanceKey(
    val purpose: ConsentPurpose,
    val version: String,
)

/** One expander's own read - the whole-kind fetch already filtered down to [Loaded.acceptances] for
 * exactly this [AcceptanceKey.version]. */
internal sealed interface AcceptancesUiState {
    data object Loading : AcceptancesUiState

    data class Failed(
        val reason: NetworkFailure,
    ) : AcceptancesUiState

    /** A loaded-but-empty list is a real state (nobody has accepted this version yet), never an error. */
    data class Loaded(
        val acceptances: List<ConsentAcceptance>,
    ) : AcceptancesUiState
}

/**
 * A publish attempt's own four honest outcomes - one arm more than
 * [ago.chat.android.automation.CannedResponsesActionError] because `409` (`Document.PublishConflict`)
 * has its own retry-able remedy, distinct from a genuine [ServerRefusal].
 */
internal sealed interface ConsentPublishActionError {
    /** The client-side courtesy check's own verdict (`tenant-consent-android.md` §1.3), caught before
     * the network is ever touched - the server remains the authoritative validator
     * ([ago.chat.android.core.domain.consent.ConsentPublishResult.Refused] is what a validation gap
     * this check missed comes back as). */
    data class Invalid(
        val problem: ConsentPublishValidationProblem,
    ) : ConsentPublishActionError

    /** `409` - two publishes for the same key raced. The correct remedy is an identical retry; the
     * publish button stays enabled on this arm. */
    data object Conflict : ConsentPublishActionError

    /** The server's own validation sentence (`Document.Invalid`, `Document.InvalidPurpose`, …), shown
     * verbatim. */
    data class ServerRefusal(
        val detail: String,
    ) : ConsentPublishActionError

    /** A dropped connection, or a non-2xx with no `detail` to show - rendered through
     * [ago.chat.android.ui.components.networkFailureText]. */
    data class Unavailable(
        val reason: NetworkFailure,
    ) : ConsentPublishActionError
}
