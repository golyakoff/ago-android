package ago.chat.android.core.domain.accountdeletion

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-252` (`ago-console`'s own `AccountDeletionPage`/`sitesApi.ts` `eraseSite`): the port behind
 * Ещё → Администрирование → «Удалить аккаунт» — `POST /api/v1/sites/erase`, gated server-side on
 * `Ago.Chat.Domain.Permission.SiteErase` (the same `site:erase` the row itself is hidden without).
 *
 * The identical "declared here, implemented in `:core:network`" split
 * [ago.chat.android.core.domain.siteexport.SiteExportApi] establishes — the dependency rule is what puts it
 * here rather than beside the Ktor client: a view model holding an `HttpClient` directly could not be tested
 * without one, and every HTTP-shaped decision (which status means what, which base URL) belongs on the far
 * side of this interface, in the adapter. The alternative — the view model calling Ktor directly — would
 * make the confirmation-gate test below need a real network, not a fake port.
 *
 * **No `{siteId}` in the path, unlike [ago.chat.android.core.domain.siteexport.SiteExportApi].** The console
 * `eraseSite` posts to a bare `/api/v1/sites/erase` and lets the `X-Ago-Active-Site` header (attached to
 * every request by `installAgoRestDefaults`) name the account — so this port carries no site parameter, and
 * the adapter needs no `ActiveSiteSelection` of its own.
 *
 * **`202 Accepted`, not "done".** Deletion touches many rows across several stores, so `Ago.Chat.Api` hands
 * it to a `Ago.Chat.Worker` job and answers `202` — [EraseAccountResult.Requested] means the erase has
 * *started*, never that the account is gone. The console's own `AccountDeletionPage` states this plainly and
 * then polls `operators/me` before signing out; this port mirrors only the request half (one method), since
 * the Android screen ends the session on the operator's own deliberate sign-out rather than on a poll (see
 * [ago.chat.android.accountdeletion.AccountDeletionViewModel]'s own doc comment).
 */
public interface AccountDeletionApi {
    /**
     * `POST /api/v1/sites/erase` — starts erasing the whole active account. This is the irreversible action:
     * a caller must never invoke it except behind an explicit, deliberate confirmation
     * ([ago.chat.android.accountdeletion.AccountDeletionViewModel] guards it behind exactly that).
     */
    public suspend fun eraseAccount(): EraseAccountResult
}

/**
 * What starting the erase came back with — three arms, the identical shape
 * [ago.chat.android.core.domain.siteexport.RequestSiteExportResult] establishes minus its `202` id echo (the
 * console's `eraseSite` returns nothing on success — the `202` body is not read, only its status).
 */
public sealed interface EraseAccountResult {
    /** `202 Accepted` — the erase job has been queued (`Ago.Chat.Worker` will run it). Nothing is deleted
     * yet; this only means the request was accepted, the same "accepted, not done" the console's own
     * `eraseSite` treats the `202` as. */
    public data object Requested : EraseAccountResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (a permission refusal, `Site.NotFound`, …),
     * shown to the operator verbatim — the same distinction from [Failed] the console draws between a real
     * `ApiProblemError.message` and its generic fallback. */
    public data class Refused(
        val detail: String,
    ) : EraseAccountResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : EraseAccountResult
}
