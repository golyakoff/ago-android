package ago.chat.android.signin

import ago.chat.android.core.domain.identity.RoutingFailure
import ago.chat.android.core.domain.identity.Tenancy

/**
 * Everything the pre-session part of the app can be showing.
 *
 * **Not a navigation graph.** `scope-inventory.md` calls the console's `/callback` "a mechanism, not
 * a screen", and the same is true of the whole of this flow: none of these states is somewhere an
 * operator navigated to, none of them has a back stack entry worth keeping, and pressing back on any
 * of them means "leave the app", not "return to the previous probe". Navigation Compose arrives with
 * `26-16`, which owns the bottom navigation and the back-button contract and therefore owns the
 * graph's actual shape — adding it here for five states with no edges between them would be a guess
 * at that item's answer.
 *
 * Two failure states, not one, and that is `11-17` ported:
 * [SignInFailed] is Keycloak's own round trip failing, and [Unavailable] is everything after it.
 * Collapsing them is what sent two people looking at the wrong end of the system on 2026-09-03.
 */
public sealed interface SignInUiState {
    /** Before anything has been asked — the store has not even been read yet. */
    public data object Starting : SignInUiState

    /** No session. The launch screen, which names no deployment (`navigation.md`). */
    public data object SignedOut : SignInUiState

    /** A Custom Tab is open, a code is being exchanged, or the probes are in flight. */
    public data object Working : SignInUiState

    /** The identity provider itself refused, or could not be reached. */
    public data class SignInFailed(val detail: String) : SignInUiState

    /** Several tenancies and no choice yet (`adr/0068`). */
    public data class ChooseSite(val tenancies: List<Tenancy>) : SignInUiState

    /** Signed in, and nothing after that answered. Renders a retry and never a destination. */
    public data class Unavailable(val failure: RoutingFailure) : SignInUiState

    /** An owner-only identity. An honest dead end with a link out (`scope-inventory.md` §2). */
    public data object PlatformOwnerTerminal : SignInUiState

    /** Neither an operator nor the owner: the registration arm. The form itself is a later item. */
    public data object Registration : SignInUiState

    /** The placeholder surface this item ends at. `26-14` replaces it with the conversation list. */
    public data class SignedIn(val activeSiteId: String?) : SignInUiState
}
