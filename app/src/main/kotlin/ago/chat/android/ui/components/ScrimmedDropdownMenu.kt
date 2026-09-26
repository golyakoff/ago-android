package ago.chat.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * `26-177`: every `DropdownMenu` in the app dims the screen behind it while open, matching the mockup and
 * this app's own [androidx.compose.material3.ModalBottomSheet]s — which already dim through
 * `BottomSheetDefaults.ScrimColor` (`MaterialTheme.colorScheme.scrim` at Material3's own `ScrimTokens`
 * opacity, 32%). Material3's `DropdownMenu` itself draws no scrim at all — it is a plain `Popup`, positioned
 * beside its anchor — so a tap outside it today falls straight through to whatever is behind. This
 * composable is the one fix for that, reused by every call site in the app rather than six copies of the
 * same `Box`: the Записи `⋮` hub (`BookingsScreen.kt`'s own `BookingsConfigMenu`), the avatar/account menu
 * (`AccountAvatarAction`), the analytics reports overflow (`AnalyticsReportsOverflowMenu`), the conversation
 * list's filter menu, and the contact panel's own tag-add and contact-actions menus.
 *
 * **Two separate [Popup]s, not one, and not a hand-rolled reimplementation of `DropdownMenu`'s own anchor
 * positioning.** Android stacks windows in the order they are added to the `WindowManager` — composing the
 * scrim's `Popup` immediately ahead of the real [DropdownMenu] in the same recomposition means the scrim's
 * window is added first and therefore sits *below* the menu's own. That is what lets a tap on an actual
 * menu item still reach [DropdownMenu] while a tap anywhere else on the dimmed screen is consumed by the
 * scrim instead of falling through to the app content underneath — the same "the scrim eats the tap" shape
 * a [androidx.compose.material3.ModalBottomSheet]'s own scrim already gives for free, restated here because
 * `DropdownMenu` does not give it. The scrim `Popup`'s own dismiss paths
 * (`dismissOnBackPress`/`dismissOnClickOutside`) are switched off so there is exactly one way to close a
 * menu — [onDismissRequest] — reached either by this composable's own tap handler or by [DropdownMenu]'s
 * own (default-on) back-press/outside-tap handling, never two independent paths racing to close the same
 * menu from two different windows.
 *
 * A design alternative considered and rejected here: converting every menu to a `ModalBottomSheet`, which
 * would get a scrim "for free" without this composable at all. Rejected because the brief is explicit that
 * a short, anchored `⋮`/avatar menu should stay a menu — a full-width sheet is the wrong shape for four or
 * five short rows anchored under a small icon, and would move every one of these menus' selection semantics
 * (`DropdownMenuItem.onClick`, tap-outside-to-dismiss at the anchor) onto a different component for no
 * behavioural gain.
 *
 * `@OptIn(ExperimentalMaterial3Api::class)`: [BottomSheetDefaults.ScrimColor] is the one experimental
 * surface this composable touches, purely to read the colour a [androidx.compose.material3.ModalBottomSheet]
 * already draws with by default — no other unstable Material3 API is used here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ScrimmedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) {
        Popup(
            properties =
                PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag(MENU_SCRIM_TEST_TAG)
                        .background(BottomSheetDefaults.ScrimColor)
                        .pointerInput(onDismissRequest) {
                            detectTapGestures(onTap = { onDismissRequest() })
                        },
            )
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
}

/** `26-177`: lets an androidTest assert the scrim is present exactly while a [ScrimmedDropdownMenu] is
 * `expanded` — the same "export a tag, don't match on styling" convention every other test tag in this
 * app follows (e.g. `TAGS_SECTION_TEST_TAG`). */
internal const val MENU_SCRIM_TEST_TAG: String = "menuScrim"
