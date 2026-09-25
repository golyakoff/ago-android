package ago.chat.android.core.domain.installation

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-159`: the port the «Установка виджета» / Channels screen (`:app`) reads through — declared here and
 * implemented in `:core:network` (`KtorInstallationApi`), the identical port/adapter split
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi] and
 * [ago.chat.android.core.domain.analytics.SiteAnalyticsApi] already establish. The dependency rule is
 * what puts it here rather than beside the Ktor client: a view model holding an `HttpClient` directly
 * could not be tested without one, and every HTTP-shaped decision (which status means what, which base
 * URL, which `{siteId}`) belongs on the far side of this interface, in the adapter.
 *
 * **Its own port, on the chat backend, not a method on a calendar port.** This mirrors `ago-console`'s
 * own `InstallSnippetPage`/`installationApi.ts`: the chat widget's public key and allowed origins are a
 * *chat* site/channel setting, read from `Ago.Chat.Api` — a different backend and a different noun than
 * the calendar-configuration writes the Записи «Настройка» screen owns. Reusing [BookingsQueueFailure]
 * across the boundary is deliberate: despite its name it answers only "is it me, or is it broken", the
 * one classification this read needs, and a second identical enum would be one more shape for the app's
 * own message rules to have to find (that enum's own doc comment already records the name is historical).
 *
 * **Read-only, mirroring the console.** `ago-console`'s `InstallSnippetPage` renders the allowed origins
 * read-only, because `Ago.Chat.Api` exposes no `site:configure`-gated write for them — the only write is
 * the platform-owner-only `PUT /api/v1/owner/sites/{siteId}/allowed-origins`. So this port carries the
 * one read the screen needs and no save; a tenant-facing edit would need a new chat endpoint first (see
 * this item's hand-off), and inventing a client call for a route that does not exist is exactly the
 * "worse than an honest gap" `InstallSnippetPage`'s own history warns against.
 */
public interface InstallationApi {
    /**
     * `GET /api/v1/sites/{siteId}/installation` — the same read `ago-console`'s own `fetchSiteInstallation`
     * makes, reduced by the adapter to the two things this screen draws: the public embed key and the
     * allowed-origins list. The four install-state timestamps, `usedRecently`, `state`, and the `23-07`
     * funnel the same response also carries are for a status/health screen this item does not build, so
     * the adapter declares and discards none of them (`ignoreUnknownKeys`).
     */
    public suspend fun fetchInstallation(): SiteInstallationResult
}

/**
 * `26-159`: the two things the «Установка виджета» screen draws, lifted out of the far larger
 * `Ago.Chat.Api.Sites.SiteInstallationEndpoints.SiteInstallationResponse`.
 */
public data class SiteInstallation(
    /** What the tenant pastes into their page's `<script>` tag as `data-site`. Not a secret (`adr/0029`),
     * but returned only to the site's own `site:configure` operators — the composed snippet is built from
     * it in the view model, the identical shape `ago-console`'s own `InstallSnippetPage` composes. */
    val publicKey: String,
    /** The origins the widget's browser-side check will compare against, in the order the server returned
     * them. Read-only here — see [InstallationApi]'s own doc comment for why there is no save. */
    val allowedOrigins: List<String>,
)

/**
 * `26-159`: what reading the installation came back with — the identical two-way "loaded, or a
 * classified failure" shape the app's read ports already establish. There is deliberately **no**
 * `NotConfigured` arm (unlike [ago.chat.android.core.domain.schedule.WorkingHoursResult]): the chat
 * installation always exists in a chat deployment (its base URL is always configured, unlike the
 * optional calendar backend), so "the product is not installed here" is not a state this read can be in.
 */
public sealed interface SiteInstallationResult {
    public data class Loaded(
        val installation: SiteInstallation,
    ) : SiteInstallationResult

    /** [BookingsQueueFailure] reused again — this read reduces to the same "is it me, or is it broken"
     * two-way question every other read in this app answers with it, never a fabricated message. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : SiteInstallationResult
}
