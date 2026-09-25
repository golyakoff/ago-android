package ago.chat.android.core.domain.tags

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-115`: the port the contact-detail panel's future tags section reads and writes through — the
 * identical split [ago.chat.android.core.domain.conversations.ConversationsApi] already establishes.
 *
 * Confirmed against the real `ago-chat` code (`TagEndpoints.cs`) rather than assumed from the `26-111`
 * design doc's own element map. That file's own doc comment states the permission split this port
 * mirrors in its two route families: the tag **vocabulary** is a per-site resource, read at
 * `/api/v1/sites/{siteId}/tags`; applying or removing an existing tag on one conversation is a
 * narrower, per-conversation write at `/api/v1/conversations/{conversationId}/tags`. Creating, renaming
 * or deleting a vocabulary entry is site configuration, not this panel's job — out of scope here, the
 * same way `docs/backlog/26-115-*.md`'s own Scope names only "read the site's tag vocabulary... apply +
 * remove."
 */
public interface ConversationTagsApi {
    /**
     * `GET /api/v1/sites/{siteId}/tags`, `RequireOperatorIdentity`-gated. The whole tag vocabulary this
     * site has defined — what the "+ метка" picker offers, minus whatever [fetchConversationTags] already
     * says is applied (that subtraction is a UI concern, not this port's).
     */
    public suspend fun fetchSiteTags(): TagVocabularyResult

    /**
     * `GET /api/v1/conversations/{conversationId}/tags`, `RequireOperatorIdentity`-gated. The tags
     * already applied to this one conversation, each carrying [ConversationTag.source]
     * (`Ago.Chat.Domain.TagSource`'s own wire spelling, `"Operator"`/`"Ai"`, unparsed — the identical
     * raw-string discipline [ContactDetail.kind][ago.chat.android.core.domain.contactdetails.ContactDetail.kind]
     * already follows).
     */
    public suspend fun fetchConversationTags(conversationId: String): ConversationTagsResult

    /** `POST /api/v1/conversations/{conversationId}/tags/{tagId}`, gated `conversation:tag` server-side
     * — no request body, the identical "the route already names both resources" shape
     * [ago.chat.android.core.domain.conversations.ConversationsApi.claim] documents. `204` on success. */
    public suspend fun applyTag(
        conversationId: String,
        tagId: String,
    ): TagActionResult

    /** `DELETE /api/v1/conversations/{conversationId}/tags/{tagId}`, gated `conversation:tag` — the
     * identical shape [applyTag] documents. `204` on success. */
    public suspend fun removeTag(
        conversationId: String,
        tagId: String,
    ): TagActionResult
}

/** `TagEndpoints.TagResponseDto` — one entry in a site's own tag vocabulary. */
public data class Tag(
    val id: String,
    val name: String,
    val createdAt: String,
)

/** `TagEndpoints.ConversationTagResponseDto` — one tag applied to one conversation. Carries [source] in
 * addition to every field [Tag] has, rather than reusing [Tag] with a nullable source — restated for the
 * identical reason [ago.chat.android.core.domain.bookings.PhoneReveal]'s own doc comment gives for
 * restating rather than widening a sibling type: the two responses are never interchangeable at a call
 * site, and a nullable field only one of them ever populates would let a caller forget which is which. */
public data class ConversationTag(
    val id: String,
    val name: String,
    val createdAt: String,
    val source: String,
)

/** What reading a site's own tag vocabulary came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.conversations.QueueResult] already establishes. */
public sealed interface TagVocabularyResult {
    public data class Loaded(
        val tags: List<Tag>,
    ) : TagVocabularyResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : TagVocabularyResult
}

/** What reading one conversation's applied tags came back with — restated from [TagVocabularyResult]
 * rather than shared, since [Loaded] carries [ConversationTag], not [Tag]. */
public sealed interface ConversationTagsResult {
    public data class Loaded(
        val tags: List<ConversationTag>,
    ) : ConversationTagsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ConversationTagsResult
}

/** What applying or removing one tag came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.conversations.ClaimResult] already establishes; [applyTag]/[removeTag]
 * share this one result type rather than each inventing its own, since both reduce to the same
 * `204`-or-refusal-or-failure question with nothing further to distinguish. */
public sealed interface TagActionResult {
    public data object Succeeded : TagActionResult

    public data class Refused(
        val detail: String,
    ) : TagActionResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : TagActionResult
}
