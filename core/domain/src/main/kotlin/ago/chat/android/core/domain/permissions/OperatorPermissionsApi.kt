package ago.chat.android.core.domain.permissions

import ago.chat.android.core.domain.identity.ProbeFailure

/**
 * The port the app's shell reads [OperatorPermissions] through — declared here, implemented in
 * `:core:network` (`KtorOperatorPermissionsApi`), the identical ports-and-adapters split
 * [ago.chat.android.core.domain.identity.IdentityApi] already draws for the sign-in probes.
 *
 * **A second, independent call to the same endpoint [ago.chat.android.core.domain.identity.IdentityApi.probeOperatorSeat]
 * already makes, deliberately, not a refactor of that one.** `ago-console`'s own
 * `operatorsApi.ts` calls `GET /api/v1/operators/me` twice on a first sign-in for the identical
 * reason: once from `resolveOperatorState` (`CallbackPage`'s routing, discards the body — exactly
 * what `probeOperatorSeat` does here) and once from `PermissionsProvider` once the operator screen is
 * actually mounted (reads the body's `permissions` field) — its own doc comment names this "a small,
 * stated, one-time duplicate fetch, not worth a cross-page cache for a call this cheap and this rare".
 * The alternative — widening [ago.chat.android.core.domain.identity.ProbeOutcome] to carry a
 * permission set — would force every caller of the *routing* probe (including the owner-eligibility
 * probe, which never has permissions to carry) to reason about a payload that means nothing to it, and
 * would tie the well-tested `PostSignInRouter` arm-by-arm suite to a shape the shell, not the router,
 * actually needs. Reusing the console's already-shipped precedent here is simpler and lower-risk than
 * either.
 */
public interface OperatorPermissionsApi {
    /** `GET /api/v1/operators/me`, gated by `RequireOperatorIdentity` — the identical endpoint and
     * policy [ago.chat.android.core.domain.identity.IdentityApi.probeOperatorSeat] reads, called a
     * second time here for its body rather than its status alone. */
    public suspend fun fetchMyPermissions(): PermissionsFetch
}

/** The three-valued discipline every other probe in this app already follows
 * ([ago.chat.android.core.domain.identity.ProbeOutcome]'s own doc comment) — a fetch that did not
 * answer is never folded into "holds nothing". */
public sealed interface PermissionsFetch {
    public data class Loaded(
        val granted: Set<String>,
    ) : PermissionsFetch

    public data class Failed(
        val reason: ProbeFailure,
    ) : PermissionsFetch
}
