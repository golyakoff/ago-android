package ago.chat.android.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * `26-285`: whether [VisitorAvatar] draws the anonymous visitor's emoji-pair badge or an initials
 * circle — a per-device UI preference, the identical category [ago.chat.android.ui.theme.ThemeMode]
 * (`26-17`) and [ago.chat.android.ui.language.AppLanguage] (`26-92`) already are. [Emoji] is the first
 * entry and this enum's own default everywhere a fallback is needed (the `Flow`'s initial value, this
 * `CompositionLocal`'s own default, [ago.chat.android.shell.SettingsViewModel.avatarStyle]'s
 * `stateIn` seed) — the author's own "the emoji-pair avatar is a great default" framing, restated as
 * code rather than merely as a comment on one of those call sites.
 *
 * Named people are unaffected by either value — see [VisitorAvatar]'s own doc comment for why this
 * toggle governs only the anonymous emoji-pair avatar, never [initialsFor]'s name-derived one.
 */
public enum class VisitorAvatarStyle {
    Emoji,
    Initials,
}

/**
 * `26-285`: the ambient read every [VisitorAvatar] call site uses, following the identical
 * `staticCompositionLocalOf` shape [ago.chat.android.ui.theme.LocalAgoStatusColors] already
 * establishes for a rarely-changing UI value with one obvious owner — see this item's own design note
 * (`docs/backlog/26-285-*.md`, §teaching) for why a `CompositionLocal` beats threading a parameter
 * through three unrelated view models. Provided once, in [ago.chat.android.MainActivity.setContent],
 * from [ago.chat.android.shell.SettingsViewModel]'s own persisted value — every call site below that
 * provider point reads the current choice for free, with zero signature changes.
 */
public val LocalVisitorAvatarStyle: ProvidableCompositionLocal<VisitorAvatarStyle> =
    staticCompositionLocalOf { VisitorAvatarStyle.Emoji }
