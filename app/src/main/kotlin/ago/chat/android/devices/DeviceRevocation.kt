package ago.chat.android.devices

/**
 * `26-06`: the one thing `AgoAuthSession.completeSignOut()` (`signOut()` until `26-93` split it in
 * two) needs from the whole device-registration story - narrowed to exactly this because a test of
 * *that ordering* ("revoke before the token is discarded")
 * has no business also faking [DeviceRegistrationCoordinator]'s registration and scheduling methods,
 * the same "depend on the narrowest thing this class actually uses" reasoning
 * [ago.chat.android.session.OperatorIdentityProvider]'s own doc comment states for splitting itself
 * off `SignInSession` rather than widening it.
 *
 * **Never throws.** Both of its own two calls - `DELETE /api/v1/me/devices/{id}` and
 * `RuStorePushClient.deleteToken()` - are best-effort on sign-out: a phone offline at the moment of
 * sign-out leaves a device row the provider's own token-invalidation path
 * (`docs/architecture/push-notifications.md` §Revocation) will eventually clean up, and a sign-out
 * must never itself fail because a network call inside it did.
 */
public interface DeviceRevocation {
    /** Revokes this installation's device row, then deletes the local push token - in that order,
     * because the revoke call is what still can, since sign-out has not cleared the session yet. */
    public suspend fun revokeThisDevice()
}
