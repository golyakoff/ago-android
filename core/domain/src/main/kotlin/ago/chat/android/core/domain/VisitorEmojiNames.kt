package ago.chat.android.core.domain

/**
 * `26-30`: a nameless visitor's fallback label used to be the bare short code — nothing a person reads
 * as words. This table gives each member of `Ago.Chat.Domain.VisitorEmojiDictionary`'s
 * `Creatures`/`Foods` a real Russian name, so the fallback can read as `Лиса · Апельсин` instead — the
 * exact derivation `ago-console/src/i18n/visitorEmojiNames.ts` already ships, ported.
 *
 * **Byte-for-byte, not retyped.** Every glyph key below was copy-pasted directly out of
 * `ago-chat/src/Ago.Chat.Domain/VisitorEmojiDictionary.cs`'s own `Creatures`/`Foods` arrays — never
 * typed fresh from an OS emoji picker or a search result, and never from `visitorEmojiNames.ts` either.
 * This matters concretely: several common emoji carry an invisible Unicode variation selector
 * (`U+FE0F`) that makes two glyphs that render identically on screen byte-different strings.
 * `ConversationSummary.emojiCreature`/`emojiFood` cross the wire as whatever `VisitorEmojiDictionary.cs`
 * literally contains, so a key here that is even one invisible codepoint off from that source silently
 * fails the lookup — no error, [localizedEmojiName] below just falls through to its documented
 * fallback. Keying by copy-pasted glyph, plus `VisitorEmojiNamesTest`'s own completeness check (a
 * second, independent copy-paste of the same 40 glyphs, asserting every one resolves), is the
 * two-source cross-check this item's own backlog asks for in place of "we were careful once".
 *
 * **Russian only, shaped so a second locale is additive.** The app has one locale
 * (`ago-android/docs/architecture.md`), unlike the console, which already carries an English table
 * alongside this one (`visitorEmojiNamesEn`/`visitorEmojiNamesRu`). [VisitorEmojiNames] is an object
 * with one property per locale for exactly that reason — a future `en` property joins `ru` here without
 * moving or rewriting anything, the identical shape the console already proves out.
 */
public object VisitorEmojiNames {
    public val ru: Map<String, String> = CREATURE_NAMES_RU + FOOD_NAMES_RU
}

// Copied byte-for-byte from `VisitorEmojiDictionary.Creatures` (`ago-chat`) - do not retype.
private val CREATURE_NAMES_RU: Map<String, String> =
    mapOf(
        "🐔" to "Курица",
        "🐠" to "Рыбка",
        "🐳" to "Кит",
        "🐶" to "Собака",
        "🐱" to "Кошка",
        "🐭" to "Мышь",
        "🐹" to "Хомяк",
        "🐰" to "Кролик",
        "🦊" to "Лиса",
        "🐻" to "Медведь",
        "🐼" to "Панда",
        "🐨" to "Коала",
        "🐯" to "Тигр",
        "🦁" to "Лев",
        "🐮" to "Корова",
        "🐷" to "Свинья",
        "🐸" to "Лягушка",
        "🐵" to "Обезьяна",
        "🐦" to "Птица",
        "🦉" to "Сова",
    )

// Copied byte-for-byte from `VisitorEmojiDictionary.Foods` (`ago-chat`) - do not retype.
private val FOOD_NAMES_RU: Map<String, String> =
    mapOf(
        "🍊" to "Апельсин",
        "🥝" to "Киви",
        "🌭" to "Хот-дог",
        "🍕" to "Пицца",
        "🍔" to "Бургер",
        "🍟" to "Картофель фри",
        "🌮" to "Тако",
        "🍣" to "Суши",
        "🍩" to "Пончик",
        "🍪" to "Печенье",
        "🍦" to "Мороженое",
        "🍎" to "Яблоко",
        "🍌" to "Банан",
        "🍇" to "Виноград",
        "🍉" to "Арбуз",
        "🍓" to "Клубника",
        "🍒" to "Вишня",
        "🍑" to "Персик",
        "🥑" to "Авокадо",
        "🍍" to "Ананас",
    )

/**
 * The one place [VisitorEmojiNames] is read — every call site goes through this rather than indexing
 * the map directly, so the "what if the glyph is missing" decision lives once
 * (`ago-console/src/i18n/visitorEmojiNames.ts`'s own `localizedEmojiName`, ported).
 *
 * **Fallback: the raw glyph itself, never a blank string.** A lookup can miss for a glyph this table's
 * own completeness test does not yet know to check — a future `VisitorEmojiDictionary.cs` member added
 * there before this table catches up, for instance. A blank string in that case would render
 * `" · Клубника"` (or worse, `" · "` for a double miss) — a gap a reader cannot make sense of. The bare
 * glyph is not a translated name, but it is still the same visual identifier the avatar itself shows,
 * so this degrades to "as readable as this table did not exist yet" rather than to nothing.
 */
public fun localizedEmojiName(
    glyph: String,
    names: Map<String, String> = VisitorEmojiNames.ru,
): String = names[glyph] ?: glyph
