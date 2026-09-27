package ago.chat.android.core.domain.consent

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §2.1): the port behind Администрирование →
 * «Документы согласий» — `GET`/`POST /api/v1/sites/{siteId}/consent-documents…`, `site:configure` on
 * every verb *inside the handler*, the same permission the row itself is gated on — the identical
 * "no rail-vs-server gap" shape [ago.chat.android.core.domain.cannedresponses.CannedResponsesApi]
 * already establishes for its own site-scoped settings form. Declared here, implemented in
 * `:core:network` (`KtorSiteConsentDocumentsApi`) — the dependency rule is what puts it here rather
 * than beside the Ktor client: a view model holding an `HttpClient` directly could not be tested
 * without one, and every HTTP-shaped decision (which status means what, which base URL, which
 * `{siteId}`/`{purpose}`) belongs on the far side of this interface, in the adapter.
 *
 * **This is the tenant-scoped surface only — it never reads a document's body.** The overview and the
 * acceptances read are metadata; a version's actual text is read through the separate anonymous
 * `/api/v1/documents/{documentKey}` surface (`tenant-consent-android.md` §1.2), which this ticket does
 * not wire — reading a version's full text on the phone is a deliberately separate item.
 */
public interface SiteConsentDocumentsApi {
    /**
     * `GET /api/v1/sites/{siteId}/consent-documents`. Both purposes' metadata in one call — the
     * screen always draws Contact and Marketing together, so there is no reason to split this into
     * two requests the way [fetchAcceptances]/[publish] are necessarily split by `{purpose}`.
     */
    public suspend fun fetchOverview(): SiteConsentDocumentsResult

    /**
     * `GET /api/v1/sites/{siteId}/consent-documents/{purpose}/acceptances`. Returns every acceptance
     * for the whole document kind, across every version — the caller filters by
     * [ConsentAcceptance.documentVersion] to scope a result to one version, mirroring
     * `ago-console`'s own `AcceptancesList` rather than asking the backend for a version-scoped
     * endpoint that was checked and found unnecessary (`23-37`/`25-21`).
     */
    public suspend fun fetchAcceptances(purpose: ConsentPurpose): ConsentAcceptancesResult

    /**
     * `POST /api/v1/sites/{siteId}/consent-documents/{purpose}`, body `{title, body}`. Always a *new*
     * version — there is no edit-in-place. `409` (`Document.PublishConflict`) is the one refusal worth
     * a dedicated arm: the correct remedy is an identical retry, not a fix, so it is
     * [ConsentPublishResult.Conflict] rather than folded into [ConsentPublishResult.Refused].
     */
    public suspend fun publish(
        purpose: ConsentPurpose,
        title: String,
        body: String,
    ): ConsentPublishResult
}

/** `VisitorConsentPurpose`'s two wire spellings, verbatim — [slug] is exactly what the URL and the
 * request body carry, never derived or guessed at a call site. */
public enum class ConsentPurpose(
    public val slug: String,
) {
    Contact("Contact"),
    Marketing("Marketing"),
}

/** One published version's metadata — never a body (`tenant-consent-android.md` §1: "the tenant-scoped
 * overview carries version *metadata* only"). */
public data class ConsentVersion(
    val version: String,
    val sequence: Int,
    val title: String,
    val publishedAt: Instant,
)

/** One purpose's whole document — [versions] newest-first, empty when nothing has been published yet
 * for this purpose. */
public data class ConsentDocumentSummary(
    val purpose: ConsentPurpose,
    val documentKey: String,
    val versions: List<ConsentVersion>,
)

/** The whole overview read. [contactConsentRequired] sits beside [contact], not inside it — a fact
 * about the site's own `WidgetConfig.RequireContactConsent`, not a fact about the document itself. */
public data class ConsentOverview(
    val contact: ConsentDocumentSummary,
    val contactConsentRequired: Boolean,
    val marketing: ConsentDocumentSummary,
)

/** One row of "who accepted". No `clientIp`/`userAgent` — narrower than the domain record by
 * deliberate design (`adr/0146`; `docs/architecture/personal-data.md`). [subjectId] is kept a `String`
 * (the GUID text): the app never treats it as a typed id, only displays and copies it. */
public data class ConsentAcceptance(
    val subjectKind: String,
    val subjectId: String,
    val documentVersion: String,
    val acceptedAt: Instant,
)

/** `PublishedDocumentVersion`'s own bounds (`ago-chat` domain), restated here as the one place both
 * the adapter's tests and `:app`'s client-side courtesy check
 * (`ago.chat.android.documents.validateConsentPublishDraft`) read them from — the identical
 * "one source, several readers" reasoning
 * [ago.chat.android.core.domain.cannedresponses.CannedResponseBounds] already follows. */
public object ConsentDocumentBounds {
    /** Mirrors `PublishedDocumentVersion.MaxTitleLength`. Title is trimmed, client and server. */
    public const val MAX_TITLE_LENGTH: Int = 200

    /** Mirrors `PublishedDocumentVersion.MaxBodyLength`. Body is stored as typed, never trimmed. */
    public const val MAX_BODY_LENGTH: Int = 100_000
}

/** What reading the overview came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.cannedresponses.CannedResponsesResult] already establishes. */
public sealed interface SiteConsentDocumentsResult {
    public data class Loaded(
        val overview: ConsentOverview,
    ) : SiteConsentDocumentsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : SiteConsentDocumentsResult
}

/** What reading one purpose's whole acceptance history came back with. A loaded-but-empty list is a
 * real, expected state (nobody has accepted yet), never a failure. */
public sealed interface ConsentAcceptancesResult {
    public data class Loaded(
        val acceptances: List<ConsentAcceptance>,
    ) : ConsentAcceptancesResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ConsentAcceptancesResult
}

/** What publishing a new version came back with — four arms, not three, because `409` has a distinct,
 * honest remedy ("retry the identical request") that a generic [Refused] would obscure behind the same
 * inline-error UI a genuine validation refusal gets. */
public sealed interface ConsentPublishResult {
    /** A `2xx` carrying the server's own echo, reduced to the metadata the overview reload will show
     * again anyway — the view model never trusts this over a fresh [SiteConsentDocumentsApi.fetchOverview]. */
    public data class Published(
        val version: ConsentVersion,
    ) : ConsentPublishResult

    /** `409` (`Document.PublishConflict`) — two publishes for the same key raced. The correct remedy
     * is to retry the identical request, not to change anything the operator typed. */
    public data object Conflict : ConsentPublishResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (`Document.Invalid`,
     * `Document.InvalidPurpose`, `Site.NotFound`, …), shown to the operator verbatim. */
    public data class Refused(
        val detail: String,
    ) : ConsentPublishResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : ConsentPublishResult
}
