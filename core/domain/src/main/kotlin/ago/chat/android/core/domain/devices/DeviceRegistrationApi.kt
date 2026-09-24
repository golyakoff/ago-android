package ago.chat.android.core.domain.devices

/**
 * `26-06`/`adr/0180`: the port behind `PUT`/`DELETE /api/v1/me/devices/{installationId}` -
 * `docs/architecture/push-notifications.md`'s own "Device registration" section names both routes and
 * the three call sites that use [register] (every sign-in, [ago.chat.android.core.domain.devices]'s
 * own push-rotation callback, and a periodic job). Declared here and implemented in `:core:network`
 * (`KtorDeviceRegistrationApi`) - the identical [ago.chat.android.core.domain.conversations.ConversationsApi]
 * split, for the identical reason: a caller that held an `HttpClient` directly could not be tested
 * without one, and every HTTP-shaped decision belongs on the far side of this interface.
 *
 * **A plain `Boolean`, not a sealed result.** Both routes are idempotent upserts/deletes this app never
 * shows to an operator - there is no refusal here for a caller to render verbatim the way
 * [ago.chat.android.core.domain.conversations.ClaimResult.Refused] renders one. `true` on a `2xx`,
 * `false` for everything else (transport failure and a genuine server refusal alike), the identical
 * shape [ago.chat.android.core.domain.conversations.ConversationsApi.markRead] already establishes for
 * a caller with no reason left to distinguish *how* a write failed, only *whether* it landed.
 */
public interface DeviceRegistrationApi {
    /**
     * `PUT /api/v1/me/devices/{installationId}` - upserts this installation's row with the push
     * [token] it holds right now. Safe to call repeatedly with the same token (the row's own
     * `last_seen_at` still advances, which is what turns a periodic call into a liveness signal) and
     * safe to call again with a new one after rotation.
     */
    public suspend fun register(
        installationId: String,
        token: String,
    ): Boolean

    /**
     * `DELETE /api/v1/me/devices/{installationId}` - revokes exactly this installation's row. `204`
     * even when no such row exists (the route's own idempotence), which is why this never needs a
     * third outcome for "there was nothing to revoke".
     */
    public suspend fun revoke(installationId: String): Boolean
}
