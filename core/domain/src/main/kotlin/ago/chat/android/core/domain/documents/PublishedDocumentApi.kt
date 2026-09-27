package ago.chat.android.core.domain.documents

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §2.2): the anonymous, no-site, no-bearer surface a
 * version's own *text* is read through — `GET /api/v1/documents/{documentKey}` (current) and
 * `GET /api/v1/documents/{documentKey}/versions/{version}` (a specific, immutable one). Declared here,
 * implemented in `:core:network` (`KtorPublishedDocumentApi`) — the dependency rule is what puts it here
 * rather than beside the Ktor client: a view model holding an `HttpClient` directly could not be tested
 * without one, and every HTTP-shaped decision (current-vs-version route, which status means what) belongs
 * on the far side of this interface, in the adapter.
 *
 * **A second, deliberately separate port from [ago.chat.android.core.domain.consent.SiteConsentDocumentsApi]**
 * — that design doc's own §2 records why: the tenant-scoped surface rides `activeSite.currentSiteId()` and
 * the bearer/`X-Ago-Active-Site` defaults every authenticated request gets, while this one is anonymous and
 * must carry neither. Folding a `fetchBody` method onto that port would mix two call shapes and hand an
 * anonymous route credentials it neither needs nor should see. It is also the exact port the design doc's
 * own planned `…/policies/{key}` deep-link reader (`navigation.md`) is meant to reuse untouched — reason
 * enough for its own home now rather than a shared one that would need splitting later.
 */
public interface PublishedDocumentApi {
    /**
     * `version == null` reads the **current** version (`GET /api/v1/documents/{documentKey}`, cached
     * `public, max-age=300` server-side — a moving pointer); a non-null `version` reads that **specific**
     * one (`GET .../versions/{version}`, `public, max-age=86400, immutable` — a value that never changes
     * once published). Two routes, not one with a query parameter, because the backend itself treats them
     * as differently cacheable (`DocumentEndpoints`'s own remark) — the app just follows that split rather
     * than flattening it back into one call shape.
     */
    public suspend fun fetchDocument(
        documentKey: String,
        version: String?,
    ): PublishedDocumentResult
}

/** One published version's own text — the reader's whole payload. */
public data class PublishedDocument(
    val documentKey: String,
    val version: String,
    val title: String,
    val body: String,
    val publishedAt: Instant,
)

/**
 * What reading one version's text came back with. [NotFound] is kept distinct from [Failed] — a `404`
 * (`Document.NotFound`) is a rare, genuine race (the version row existed in the tenant-scoped overview a
 * moment ago but is gone by read time, e.g. a purge), so the reader says "this version is no longer
 * available" rather than offering a retry that can only fail the same way again.
 */
public sealed interface PublishedDocumentResult {
    public data class Loaded(
        val document: PublishedDocument,
    ) : PublishedDocumentResult

    public data object NotFound : PublishedDocumentResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : PublishedDocumentResult
}
