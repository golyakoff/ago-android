package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.domain.VisitorEmojiPair
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * `26-116`: the contact-detail panel's own localized emoji-pair fallback
 * (`docs/design/26-111-thread-contact-detail-panel.md`, H2 - «Сова · Клубника») - shown when a visitor
 * has no real name. Per that design's Author decisions, this is the *only* fallback: never a raw
 * hex/shortId on screen.
 *
 * **A second, deliberately separate table from `:core:domain`'s [ago.chat.android.core.domain.VisitorEmojiNames].**
 * That one is Russian-only, hardcoded Kotlin literals - correct for `26-30`, which shipped it before
 * this app had a second locale or the "strings are resources" rule (`26-91`) existed, and it still backs
 * two already-shipped screens ([ago.chat.android.core.domain.visitorFallbackLabel]'s own two call
 * sites: the conversation-list row and the thread app-bar title) this item has no mandate to touch.
 * `:core:domain` is a plain Kotlin JVM module with no Android Gradle plugin at all
 * (`docs/architecture.md`, "Module layout") - it structurally cannot hold an Android string resource -
 * so a *resource-backed* version, in both `ru` and `en`, can only live here, in `:app`, which is why
 * this item adds a second table rather than editing the first.
 *
 * **Byte-for-byte, not retyped.** Every glyph key below is copy-pasted directly out of
 * `ago-chat/src/Ago.Chat.Domain/VisitorEmojiDictionary.cs`'s own `Creatures`/`Foods` arrays - the same
 * discipline `VisitorEmojiNames.kt`'s own doc comment applies, and for the same reason: several common
 * emoji carry an invisible Unicode variation selector (`U+FE0F`) that makes two glyphs that render
 * identically on screen byte-different strings, so a key here that is even one invisible codepoint off
 * from that source silently fails the lookup - no error, [visitorEmojiPairName] below just falls
 * through to its documented fallback. [VisitorEmojiPairNameTest]'s own completeness check is the
 * independent, second transcription of the same 40 glyphs this codebase's own testing convention
 * already asks for.
 */
@Composable
public fun visitorEmojiPairName(pair: VisitorEmojiPair): String {
    // `stringResource` may only be called from a `@Composable` body, so the two lookups happen here -
    // [visitorEmojiNameResource] (which glyph maps to which resource id) and [visitorEmojiPairNameText]
    // (how the two resolved names are joined) are both plain functions precisely so a plain JVM `test`
    // can drive each directly, the same split `ElapsedText.kt`'s own `russianPluralStringResource` /
    // `localePluralResourceId` already uses for the identical reason.
    val creatureName = visitorEmojiNameResource(pair.creature)?.let { stringResource(it) } ?: pair.creature
    val foodName = visitorEmojiNameResource(pair.food)?.let { stringResource(it) } ?: pair.food
    return visitorEmojiPairNameText(creatureName, foodName)
}

/**
 * The plain join rule behind [visitorEmojiPairName] - pulled out so a plain JVM `test` can assert the
 * exact composition (separator, order) without needing a composition host, and so a test that wants the
 * *rendered words* can pass its own mirrored strings straight in rather than resolving through
 * [stringResource].
 */
internal fun visitorEmojiPairNameText(
    creatureName: String,
    foodName: String,
): String = "$creatureName · $foodName"

/**
 * The one place [CREATURE_NAME_RESOURCES]/[FOOD_NAME_RESOURCES] are read. `null` for a glyph neither
 * table knows - a future `VisitorEmojiDictionary.cs` member added there before this table catches up,
 * for instance - so [visitorEmojiPairName] can fall back to the bare glyph itself, never a blank string,
 * the identical fallback [ago.chat.android.core.domain.localizedEmojiName] already documents for the
 * Russian-only table.
 */
@StringRes
internal fun visitorEmojiNameResource(glyph: String): Int? = EMOJI_NAME_RESOURCES[glyph]

// Copied byte-for-byte from `VisitorEmojiDictionary.Creatures` (`ago-chat`) - do not retype.
private val CREATURE_NAME_RESOURCES: Map<String, Int> =
    mapOf(
        "🐔" to R.string.visitor_emoji_name_chicken,
        "🐠" to R.string.visitor_emoji_name_fish,
        "🐳" to R.string.visitor_emoji_name_whale,
        "🐶" to R.string.visitor_emoji_name_dog,
        "🐱" to R.string.visitor_emoji_name_cat,
        "🐭" to R.string.visitor_emoji_name_mouse,
        "🐹" to R.string.visitor_emoji_name_hamster,
        "🐰" to R.string.visitor_emoji_name_rabbit,
        "🦊" to R.string.visitor_emoji_name_fox,
        "🐻" to R.string.visitor_emoji_name_bear,
        "🐼" to R.string.visitor_emoji_name_panda,
        "🐨" to R.string.visitor_emoji_name_koala,
        "🐯" to R.string.visitor_emoji_name_tiger,
        "🦁" to R.string.visitor_emoji_name_lion,
        "🐮" to R.string.visitor_emoji_name_cow,
        "🐷" to R.string.visitor_emoji_name_pig,
        "🐸" to R.string.visitor_emoji_name_frog,
        "🐵" to R.string.visitor_emoji_name_monkey,
        "🐦" to R.string.visitor_emoji_name_bird,
        "🦉" to R.string.visitor_emoji_name_owl,
    )

// Copied byte-for-byte from `VisitorEmojiDictionary.Foods` (`ago-chat`) - do not retype.
private val FOOD_NAME_RESOURCES: Map<String, Int> =
    mapOf(
        "🍊" to R.string.visitor_emoji_name_orange,
        "🥝" to R.string.visitor_emoji_name_kiwi,
        "🌭" to R.string.visitor_emoji_name_hot_dog,
        "🍕" to R.string.visitor_emoji_name_pizza,
        "🍔" to R.string.visitor_emoji_name_burger,
        "🍟" to R.string.visitor_emoji_name_fries,
        "🌮" to R.string.visitor_emoji_name_taco,
        "🍣" to R.string.visitor_emoji_name_sushi,
        "🍩" to R.string.visitor_emoji_name_donut,
        "🍪" to R.string.visitor_emoji_name_cookie,
        "🍦" to R.string.visitor_emoji_name_ice_cream,
        "🍎" to R.string.visitor_emoji_name_apple,
        "🍌" to R.string.visitor_emoji_name_banana,
        "🍇" to R.string.visitor_emoji_name_grapes,
        "🍉" to R.string.visitor_emoji_name_watermelon,
        "🍓" to R.string.visitor_emoji_name_strawberry,
        "🍒" to R.string.visitor_emoji_name_cherry,
        "🍑" to R.string.visitor_emoji_name_peach,
        "🥑" to R.string.visitor_emoji_name_avocado,
        "🍍" to R.string.visitor_emoji_name_pineapple,
    )

private val EMOJI_NAME_RESOURCES: Map<String, Int> = CREATURE_NAME_RESOURCES + FOOD_NAME_RESOURCES
