package ago.chat.android.core.domain.tags

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2): the port behind Автоматизация → «Метки» —
 * `GET`/`POST /api/v1/sites/{siteId}/tags` and `PUT`/`DELETE /api/v1/sites/{siteId}/tags/{tagId}`, the
 * *management* half of this site's tag vocabulary. Declared here, implemented in `:core:network`
 * (`KtorSiteTagsApi`) — the same dependency-rule reasoning [CannedResponsesApi]
 * [ago.chat.android.core.domain.cannedresponses.CannedResponsesApi]'s own doc comment gives: a view model
 * cannot hold an `HttpClient` directly without becoming untestable, so every HTTP-shaped decision stays on
 * the far side of this interface, in the adapter.
 *
 * **A new port, not an extra three methods on [ConversationTagsApi].** That port's own doc comment states
 * outright that "creating, renaming or deleting a vocabulary entry is site configuration, not this
 * panel's job — out of scope here." [ConversationTagsApi] reads the vocabulary under `conversation:read`
 * for the conversation panel's own tag picker and applies/removes a tag on one conversation under
 * `conversation:tag`; this port manages the vocabulary itself under `site:configure`. Both reuse the one
 * [Tag] domain type and [TagVocabularyResult] declared alongside [ConversationTagsApi] — [fetch] here is
 * the identical `GET` that port's own `fetchSiteTags` already makes, so only that one signature is
 * shared, never duplicated (`docs/design/tenant-canned-tags-android.md` §2.2).
 *
 * **Per-row CRUD, unlike [ago.chat.android.core.domain.cannedresponses.CannedResponsesApi].** A tag is a
 * real table row with its own id (`Tag`'s own doc comment contrasts this with `CannedResponse`, which has
 * none) — [create], [rename] and [delete] each address one row and each make their own call; there is no
 * whole-list write here.
 */
public interface SiteTagsApi {
    /** `GET /api/v1/sites/{siteId}/tags`, `conversation:read`-gated server-side — the identical read
     * [ConversationTagsApi.fetchSiteTags] makes; reused as [TagVocabularyResult], never re-declared. An
     * empty vocabulary is a *loaded* empty result, not a failure. */
    public suspend fun fetch(): TagVocabularyResult

    /** `POST /api/v1/sites/{siteId}/tags`, body `{"name"}`, `site:configure`-gated server-side
     * (`CreateTagHandler`). A case-insensitive duplicate for this site comes back as
     * [TagMutationResult.Refused] carrying `Tag.AlreadyExists`'s own `detail`. */
    public suspend fun create(name: String): TagMutationResult

    /** `PUT /api/v1/sites/{siteId}/tags/{tagId}`, body `{"name"}`, `site:configure`-gated server-side
     * (`RenameTagHandler`). Every `conversation_tags` row referencing [tagId] survives a rename — the
     * join is by id, not name (`docs/design/tenant-canned-tags-android.md` §2.1). */
    public suspend fun rename(
        tagId: String,
        name: String,
    ): TagMutationResult

    /** `DELETE /api/v1/sites/{siteId}/tags/{tagId}`, `site:configure`-gated server-side
     * (`DeleteTagHandler`). **Cascades**: the schema's own `conversation_tags` FK
     * (`ReferentialAction.Cascade`) removes this tag from every conversation that carried it, and
     * re-creating a tag of the same name does not restore those associations
     * (`docs/design/tenant-canned-tags-android.md` §2.1). `204` on success. */
    public suspend fun delete(tagId: String): TagDeleteResult
}

/** `Ago.Chat.Domain.Tag.MaxNameLength`, restated here as the one place both the adapter's tests and
 * `:app`'s client-side courtesy check read it from — the identical "one source, several readers" shape
 * [ago.chat.android.core.domain.cannedresponses.CannedResponseBounds] already establishes for its own
 * port. */
public object TagBounds {
    /** Mirrors `Ago.Chat.Domain.Tag.MaxNameLength`. A tag name is trimmed, client and server. */
    public const val MAX_NAME_LENGTH: Int = 60
}

/** What creating or renaming one tag came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult] already establishes for a
 * write that can be genuinely refused, restated here rather than reused since neither port is the
 * other's concern and this one carries a single [Tag], never a list. */
public sealed interface TagMutationResult {
    /** A `2xx` carrying the server's own saved row — the view model re-fetches the vocabulary from this
     * signal rather than splicing [tag] into the in-memory list itself
     * (`docs/design/tenant-canned-tags-android.md` §2.4's own "no optimistic list edit"). */
    public data class Saved(
        val tag: Tag,
    ) : TagMutationResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (`Tag.Invalid`/`Tag.AlreadyExists`),
     * shown to the operator verbatim; nothing was written. */
    public data class Refused(
        val detail: String,
    ) : TagMutationResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : TagMutationResult
}

/** What deleting one tag came back with — the identical three-arm shape [TagMutationResult] establishes
 * for this port's other two writes, restated rather than reused since a delete carries no [Tag] to echo
 * back on success. */
public sealed interface TagDeleteResult {
    public data object Deleted : TagDeleteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (`Tag.NotFound` if another operator
     * deleted it first), shown to the operator verbatim. */
    public data class Refused(
        val detail: String,
    ) : TagDeleteResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : TagDeleteResult
}
