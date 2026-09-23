package ago.chat.android.core.domain.net

import java.io.IOException

/**
 * `26-59`: why a call to the platform could not be answered — classified, never described.
 *
 * Four adapters each used to carry their own private `Exception.describe()` extension, one that read
 * the caught exception's own `::class.simpleName` and `message` and pasted the two together, and every
 * one of them fed an operator-visible string. That was not merely unhelpful text: Ktor's OkHttp engine
 * reports a DNS failure as an [java.net.UnknownHostException] whose own `message` quotes the host it
 * failed to resolve, so a dropped connection on the sign-in screen printed this deployment's own
 * hostname next to it — exactly what `26-45`'s `SignInScreens.kt` doc comment and `scope-inventory.md`
 * §11 both say this app must never do.
 *
 * Three arms, not a `String`, because an operator's real question — "is it me, or is it broken?" —
 * has three different honest answers, the identical reasoning [ago.chat.android.core.domain.identity.RoutingFailure]
 * already applies to a routing probe's own non-answer (`11-17`).
 *
 * Declared in a package of its own, under neither `identity` nor `conversations`, because by design
 * every feature area reads it — the same "every adapter and every view model needs the same answer"
 * reasoning [ago.chat.android.core.domain.permissions.PermissionsFetch] already leaned on when it
 * reused the identity package's own failure type rather than inventing a second one. **Rendering a
 * Russian sentence from a value of this type is `:app`'s job alone** — the same split
 * [ago.chat.android.core.domain.conversations.ClaimResult.Refused]'s own `detail` field draws for a
 * genuine server refusal: the domain layer says what happened, never how to say it.
 */
public sealed interface NetworkFailure {
    /** The request never reached a server, or its answer never came back — every [IOException]: no
     * signal, a DNS failure, a refused or dropped connection, a timeout. */
    public data object NoConnection : NetworkFailure

    /** The server answered, but with a status this caller has no specific reading for — every
     * `4xx`/`5xx` an adapter does not special-case, a `503` among them. */
    public data class ServerError(
        val status: Int,
    ) : NetworkFailure

    /** Neither of the above: a `2xx` whose body was not the shape this contract promised, or any
     * caught exception that is not an [IOException]. */
    public data object Unexpected : NetworkFailure

    public companion object {
        /**
         * Classifies a caught, non-cancellation exception into [NoConnection] or [Unexpected].
         * Deliberately reads only `exception is IOException` — never `exception.message`,
         * `exception::class.simpleName`, nor a `toString()` over the exception or its cause chain,
         * which is exactly what used to leak a hostname onto the sign-in screen, and which could just
         * as easily carry a request's own `Authorization` header for a client built the way this
         * app's is.
         */
        public fun from(exception: Exception): NetworkFailure = if (exception is IOException) NoConnection else Unexpected
    }
}
