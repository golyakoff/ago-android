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
 *
 * `26-30`: [displayName] joins [visitorName] rather than replacing it — [visitorName] stays the
 * visitor's own *real* name (or `null`), exactly as before, so [visitorDisplayPrefixText] and its own
 * pre-existing tests are provably unchanged; [displayName] is the new "what a screen should actually
 * show" value — [visitorName] when there is one, else the emoji pair's own localized fallback label
 * ("Лиса · Апельсин"), else `null`. Both of this item's two real call sites — the conversation-list
 * row's identity line, and the thread screen's app-bar title (`26-40`'s own
 * `ago.chat.android.thread.ThreadTitleBlock`) — read [displayName], never [visitorName] directly, which
 * is what gives the thread screen the same fallback with no separate derivation of its own.
 *
 * `26-68`: [visitorId] is nullable, joining every other field here — a restored thread
 * (`ago.chat.android.shell.ConversationsTabHost`) can genuinely not know it yet, and the fix for that
 * item's own bug (the conversation's own id substituted into this slot, rendered as if it were a real
 * visitor short code) is this type admitting "unknown" rather than a caller inventing a value to fill
 * it. `null` means exactly what it means for [visitorName]/the emoji halves: absent, never rendered,
 * never guessed.
 */
public data class VisitorDisplayPrefixParts(
    public val emoji: VisitorEmojiPair?,
    public val visitorName: String?,
    public val displayName: String?,
    public val visitorId: String?,
)

/**
 * Builds the parts from raw, wire-shaped fields — nullable emoji halves, a nullable/blankable name,
 * and the visitor's full id, itself nullable since `26-68` for the identical reason the other three
 * already were. Mirrors `ago-console/src/workspace/visitorEmoji.ts`'s own normalisation exactly: a
 * blank name is treated as absent (that file's own doc comment — "a defensive normalisation, not an
 * expectation that the wire ever actually sends whitespace").
 */
public fun visitorDisplayPrefixParts(
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    visitorId: String?,
): VisitorDisplayPrefixParts {
    val emoji = visitorEmojiPair(emojiCreature, emojiFood)
    val name = visitorName?.trim()?.takeIf { it.isNotEmpty() }
    return VisitorDisplayPrefixParts(
        emoji = emoji,
        visitorName = name,
        displayName = name ?: visitorFallbackLabel(emoji),
        visitorId = visitorId,
    )
}

/**
 * `26-30`: the console's own `visitorFallbackLabel` (`ago-console/src/workspace/visitorEmoji.ts`),
 * ported — "Лиса · Апельсин" for a known pair, `null` (never `""`, never a lone `·`) when there is no
 * pair to derive one from at all. [visitorDisplayPrefixParts] is the only caller; kept as its own
 * top-level function rather than inlined there because [VisitorDisplayPrefixTest] exercises it
 * directly, the same "prove the composition rule, not just its one caller" reasoning that function's
 * own test file already applies to [visitorEmojiPair].
 */
public fun visitorFallbackLabel(emoji: VisitorEmojiPair?): String? =
    emoji?.let { "${localizedEmojiName(it.creature)} · ${localizedEmojiName(it.food)}" }

/**
 * The plain-text form of the composite — for the two cases that need one string rather than a styled
 * layout: these tests, and any future non-Compose caller (a notification, a log line). The
 * composition rule is unchanged from the console's own — `visitorEmoji.ts`'s `visitorDisplayPrefix`,
 * plus the short id its own two real call sites (`ConversationList.tsx`, `ConversationPage.tsx`) each
 * append after it. Each present part supplies its own trailing space (the emoji pair's, then the
 * name's), and no part ever supplies a *leading* one — so a pair-less, name-less visitor renders the
 * short id alone, with no gap where the pair or the name would have gone, never a blank placeholder.
 *
 * `26-68`: [VisitorDisplayPrefixParts.visitorId] can now be `null` too — the newly-possible case is the
 * *id* being the missing part rather than the pair or the name, so this reuses the identical "each part
 * supplies its own trailing space, nothing supplies a leading one" rule and simply trims whatever
 * trailing space the last present part left behind when the id is the one that is absent (`trimEnd()`
 * is a no-op whenever the id *is* present, since [shortId] never ends in whitespace) — never a stray
 * trailing gap where the id would have gone, the same standard every other absent part already meets.
 */
public fun visitorDisplayPrefixText(parts: VisitorDisplayPrefixParts): String {
    val emojiPart = parts.emoji?.let { "${it.creature}${it.food} " } ?: ""
    val namePart = parts.visitorName?.let { "$it " } ?: ""
    val idPart = parts.visitorId?.let { shortId(it) } ?: ""
    return "$emojiPart$namePart$idPart".trimEnd()
}
