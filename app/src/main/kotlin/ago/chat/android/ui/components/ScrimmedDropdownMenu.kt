package ago.chat.android.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
 *
 * **`26-264`: the scrim fades, it does not hard-cut.** Before this, the scrim `Box` painted
 * [BottomSheetDefaults.ScrimColor] the instant [expanded] flipped true and vanished the instant it flipped
 * false, while the [DropdownMenu] beside it eased its own content in and out — so the backdrop popped a frame
 * ahead of the menu and read as a flicker. The fix drives the scrim's opacity through a
 * [MutableTransitionState] so it fades `0 → 1 → 0` over [SCRIM_FADE_DURATION_MS] with [FastOutSlowInEasing]
 * (Material's standard ease), coherent with the menu's own enter/exit. Holding the state also keeps the
 * scrim [Popup] composed through the *exit* fade — [expanded] alone would tear it down on the first frame of
 * dismiss — so the condition below unmounts the `Popup` only once both the current and target states are
 * `false`, i.e. after the fade-out has finished.
 *
 * The menu content's own fade-and-scale is left to [DropdownMenu], which already eases its surface in from a
 * reduced scale with the transform origin anchored at the corner nearest its trigger — the exact motion the
 * `26-264` spec asks of the menu. Re-implementing it here to pin the scale start and duration to specific
 * numbers would mean re-implementing [DropdownMenu]'s own `PopupPositionProvider` (the anchor maths this
 * component's design deliberately delegates — see the note above), so the animation ticket keeps positioning
 * untouched and animates only the scrim, the part that was actually hard-cutting.
 *
 * Reduced motion is honoured: when the platform's [Settings.Global.ANIMATOR_DURATION_SCALE] is `0`
 * (developer setting or accessibility "remove animations"), the fade duration collapses to `0`, so the scrim
 * snaps in and out with no easing rather than animating against the user's stated preference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ScrimmedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrimVisibility = remember { MutableTransitionState(false) }
    scrimVisibility.targetState = expanded
    val fadeDurationMs = rememberScrimFadeDurationMs()

    // Keep the scrim window mounted through both the enter fade (target true) and the exit fade (current
    // still true while target has gone false); drop it only once the fade-out has fully settled.
    if (scrimVisibility.currentState || scrimVisibility.targetState) {
        Popup(
            properties =
                PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
        ) {
            val transition = rememberTransition(scrimVisibility, label = "menuScrim")
            val scrimAlpha by transition.animateFloat(
                transitionSpec = { tween(durationMillis = fadeDurationMs, easing = FastOutSlowInEasing) },
                label = "menuScrimAlpha",
            ) { visible -> if (visible) 1f else 0f }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag(MENU_SCRIM_TEST_TAG)
                        .alpha(scrimAlpha)
                        .background(BottomSheetDefaults.ScrimColor)
                        .pointerInput(onDismissRequest) {
                            detectTapGestures(onTap = { onDismissRequest() })
                        },
            )
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)
}

/**
 * `26-264`: the scrim fade duration in milliseconds, `0` when the system animator scale is off so reduced-motion
 * users get an instant scrim instead of an animation. Read from [Settings.Global.ANIMATOR_DURATION_SCALE] —
 * the same signal the platform's own view animations obey — via [LocalContext], keyed on the context so it is
 * resolved once per composition rather than on every frame.
 */
@Composable
private fun rememberScrimFadeDurationMs(): Int {
    val context = LocalContext.current
    return remember(context) {
        val animatorScale =
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        if (animatorScale == 0f) 0 else SCRIM_FADE_DURATION_MS
    }
}

/** `26-264`: 160 ms — the author-approved scrim fade, matching the menu's own enter/exit feel. */
private const val SCRIM_FADE_DURATION_MS: Int = 160

/** `26-177`: lets an androidTest assert the scrim is present exactly while a [ScrimmedDropdownMenu] is
 * `expanded` — the same "export a tag, don't match on styling" convention every other test tag in this
 * app follows (e.g. `TAGS_SECTION_TEST_TAG`). */
internal const val MENU_SCRIM_TEST_TAG: String = "menuScrim"
