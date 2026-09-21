package ago.chat.android.core.domain

/**
 * The two-glyph identity a visitor is assigned at creation (`Ago.Chat.Domain.Visitor`) — present or
 * absent as a whole pair, never as one half without the other (the same guarantee
 * `ago-console/src/workspace/visitorEmoji.ts`'s `hasEmojiPair` enforces on the wire DTO). Modelled as
 * two non-null strings precisely so a caller holding one cannot forget to check the other.
 */
public data class VisitorEmojiPair(
    public val creature: String,
    public val food: String,
)

/**
 * The one "is there really a pair here" check — `ago-console/src/workspace/visitorEmoji.ts`'s
 * `hasEmojiPair`/`visitorEmojiPrefix`. A visitor predating the emoji-pair column, or a row from an
 * in-flight write, has both fields null (or blank) on the wire, and that must render as "no pair" —
 * never half a badge. A blank string is treated the same as a missing one, the identical defensive
 * normalisation `visitorDisplayPrefixParts` below applies to `visitorName`.
 */
public fun visitorEmojiPair(
    emojiCreature: String?,
    emojiFood: String?,
): VisitorEmojiPair? {
    val creature = emojiCreature?.takeIf { it.isNotEmpty() }
    val food = emojiFood?.takeIf { it.isNotEmpty() }
    return if (creature != null && food != null) VisitorEmojiPair(creature, food) else null
}

/**
 * The structured form of the composite `ago-android/docs/architecture.md` names —
 * `{emojiCreature}{emojiFood} {visitorName?} {visitorId.slice(0, 8)}` — split into parts rather than
 * one string because the emoji pair renders at its own, deliberately larger size (`VisitorDisplayPrefix`
 * in `:app`), which a plain string could not carry. `visitorId` is kept full length here; it is
 * truncated only at the point of use (`shortId`, `visitorDisplayPrefixText` below, or `IdentifierText`
 * in `:app`), so this type never duplicates the truncation rule those already own.
 */
public data class VisitorDisplayPrefixParts(
    public val emoji: VisitorEmojiPair?,
    public val visitorName: String?,
    public val visitorId: String,
)

/**
 * Builds the parts from raw, wire-shaped fields — nullable emoji halves, a nullable/blankable name,
 * and the visitor's full id. Mirrors `ago-console/src/workspace/visitorEmoji.ts`'s own normalisation
 * exactly: a blank name is treated as absent (that file's own doc comment — "a defensive
 * normalisation, not an expectation that the wire ever actually sends whitespace").
 */
public fun visitorDisplayPrefixParts(
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    visitorId: String,
): VisitorDisplayPrefixParts =
    VisitorDisplayPrefixParts(
        emoji = visitorEmojiPair(emojiCreature, emojiFood),
        visitorName = visitorName?.trim()?.takeIf { it.isNotEmpty() },
        visitorId = visitorId,
    )

/**
 * The plain-text form of the composite — for the two cases that need one string rather than a styled
 * layout: these tests, and any future non-Compose caller (a notification, a log line). The
 * composition rule is unchanged from the console's own — `visitorEmoji.ts`'s `visitorDisplayPrefix`,
 * plus the short id its own two real call sites (`ConversationList.tsx`, `ConversationPage.tsx`) each
 * append after it. Each present part supplies its own trailing space (the emoji pair's, then the
 * name's), and no part ever supplies a *leading* one — so a pair-less, name-less visitor renders the
 * short id alone, with no gap where the pair or the name would have gone, never a blank placeholder.
 */
public fun visitorDisplayPrefixText(parts: VisitorDisplayPrefixParts): String {
    val emojiPart = parts.emoji?.let { "${it.creature}${it.food} " } ?: ""
    val namePart = parts.visitorName?.let { "$it " } ?: ""
    return "$emojiPart$namePart${shortId(parts.visitorId)}"
}
