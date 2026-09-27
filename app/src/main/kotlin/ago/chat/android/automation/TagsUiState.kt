package ago.chat.android.automation

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.Tag

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2.4): [TagsViewModel]'s own state — the
 * identical loading/failed/loaded shape [ago.chat.android.automation.CannedResponsesUiState] already
 * establishes for its own read-then-edit screen, sized for **per-row CRUD** rather than that screen's own
 * whole-list PUT: [Loaded.tags] is always the server's own vocabulary, re-fetched after every mutation,
 * never spliced in-place ("no optimistic list edit", `docs/design/tenant-canned-tags-android.md` §2.4).
 *
 * **Which dialog is open (create, rename-of-id, or confirm-delete-of-id) is not a state of its own.** It
 * lives as transient composition state in [TagsScreen], the identical discipline
 * [ago.chat.android.schedule.WorkingHoursScreen]'s own edit dialog already follows.
 */
internal sealed interface TagsUiState {
    data object Loading : TagsUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : TagsUiState

    /**
     * @param tags the site's own tag vocabulary, in the order the server returned it.
     * @param busy `true` while a create, rename or delete round-trip is in flight — the create/rename
     *   dialog's own Сохранить is disabled on this, and a second mutation is refused re-entrancy by
     *   [TagsViewModel] rather than racing the first.
     * @param savedTick bumped on every successful create or rename — the identical
     *   [ago.chat.android.automation.CannedResponsesUiState.Loaded.savedTick] shape, the signal
     *   [TagsScreen] uses to close the create/rename dialog only on a genuine success, never on a
     *   [TagsActionError.ServerRefusal] such as `Tag.AlreadyExists`
     *   (`docs/design/tenant-canned-tags-android.md` §2.4's own "dialog stays open"). A delete's own
     *   confirm dialog dismisses on the tap itself, before the result is known — it never reads this.
     * @param error the most recent mutation's own outcome — a local courtesy-validation problem caught
     *   before any network call, the server's own refusal, or a transport failure. `null` once a new
     *   mutation starts or one succeeds.
     */
    data class Loaded(
        val tags: List<Tag>,
        val busy: Boolean = false,
        val savedTick: Int = 0,
        val error: TagsActionError? = null,
    ) : TagsUiState
}

/**
 * A mutation attempt's own three honest outcomes — the identical split
 * [ago.chat.android.automation.CannedResponsesActionError] already establishes, restated here rather than
 * reused since neither port is the other's concern.
 */
internal sealed interface TagsActionError {
    /** The client-side courtesy check's own verdict (`docs/design/tenant-canned-tags-android.md` §2.4),
     * caught before the network is ever touched — the server remains the authoritative validator
     * ([ago.chat.android.core.domain.tags.TagMutationResult.Refused] is what a validation gap the
     * courtesy check missed comes back as, `Tag.Invalid`/`Tag.AlreadyExists` included). */
    data class Invalid(
        val problem: TagValidationProblem,
    ) : TagsActionError

    /** The server's own refusal sentence (`Tag.Invalid`, `Tag.AlreadyExists`, or `Tag.NotFound` on a
     * rename/delete another operator already removed), shown verbatim. */
    data class ServerRefusal(
        val detail: String,
    ) : TagsActionError

    /** A dropped connection, or a non-2xx with no `detail` to show — rendered through
     * [ago.chat.android.ui.components.networkFailureText]. Also what a re-fetch failure right after a
     * successful mutation becomes, since the mutation itself is not in doubt but the list shown could be
     * stale — the operator sees this and can retry with the list's own retry, never a screen that claims
     * success while showing nothing. */
    data class Unavailable(
        val reason: NetworkFailure,
    ) : TagsActionError
}
