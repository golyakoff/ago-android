package ago.chat.android.documents

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.shell.AppShellScreen
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §4.1): Администрирование → «Документы согласий» is
 * gated on `site:configure`, hide-not-disable — the identical UX-only client gate
 * [ago.chat.android.automation.CannedResponsesGatingTest]/[ago.chat.android.automation.TagsGatingTest]
 * already prove for their own sibling rows, driving the real
 * [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда — the
 * property under test is entirely inside [ago.chat.android.shell.buildMoreRows]'s own gate, which needs no
 * Hilt component to exercise, and the test never opens the row, so [ConsentDocumentsRoute]'s own
 * `hiltViewModel()` is never reached.
 *
 * **Unlike its two siblings, Администрирование itself never disappears without `site:configure`** —
 * `ADMINISTRATION_OPERATORS_ROW_ID` is an unconditional row in that same section (`MoreScreen.kt`'s own
 * `buildMoreRows`), so only «Документы согласий» itself is hidden, never the section header. (`26-301`
 * moved «Тариф и оплата» — `ADMINISTRATION_BILLING_ROW_ID` — behind the same `site:configure` gate; it no
 * longer keeps the section alive on its own, but «Операторы и роли» still does, so this claim still holds.)
 * The negative case below asserts the header (and its one remaining unconditional row) survives
 * specifically to keep that distinct from `CannedResponsesGatingTest`'s/`TagsGatingTest`'s own
 * "the whole section disappears" assertion — Автоматизация has no unconditional row of its own, so it
 * disappears entirely once its last gated row does; Администрирование does not.
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`).
 *
 * **The positive case scrolls before asserting** - unlike its two siblings, whose own row sits high
 * enough in `MoreScreen`'s `LazyColumn` to already be composed, «Документы согласий» is Администрирование's
 * third and last row, pushed below the initial viewport once `site:configure` also draws the three extra
 * Автоматизация rows above it; a `LazyColumn` never composes a semantics node for an item it has not laid
 * out, so the fix is a real scroll (`performScrollToNode`) rather than a longer wait or a relaxed
 * assertion (a CI-observed failure this file's own first version had:
 * `AssertionError: Failed: assertExists. ... could not find any node ... 'Документы согласий'`).
 */
@RunWith(AndroidJUnit4::class)
class ConsentDocumentsGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theConsentDocumentsRowAppearsUnderAdministration() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE)),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()

        // `26-246` lengthened the site:configure list (a fourth Автоматизация row) enough that the deep
        // Администрирование rows now start below the initial viewport; a `LazyColumn` composes no semantics
        // node for an unlaid item, so drive the scroll container to «Документы согласий» itself (the gated
        // row this test is about) rather than asserting an off-screen section header first. The
        // `withoutSiteConfigure` case keeps the «Администрирование» header assertion, where the shorter list
        // keeps it on screen.
        // `MoreScreen`'s own list is a `LazyColumn` - with `site:configure` granted, the three extra
        // Автоматизация rows above push «Документы согласий» (the section's third and last row) below the
        // initial viewport, so a lazy item that has never been composed has no semantics node at all yet
        // for a plain `onNodeWithText` to find. `performScrollToNode` drives the scroll container itself
        // until a matching node is composed, rather than looking one up before it exists - the identical
        // shape `androidx.compose.ui.test.performScrollToNode`'s own doc recommends for exactly this
        // "item not yet laid out" case a bare `performScrollTo()` cannot handle.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Документы согласий"))
        // `26-246` added a fourth Автоматизация row («ИИ-подсказки»), lengthening the full-`site:configure`
        // `LazyColumn` and pushing this already-deep Администрирование row further down. On a slow CI
        // emulator a single `waitForIdle()` after the scroll still races the lazy (re)layout — the assert
        // ran while «Документы согласий» was only just being composed. A **bounded poll** waits for the node
        // to actually appear (up to 5s) rather than settling once, the same shape
        // `BackContractDialogsTabTest`'s own `waitUntil`/`onAllNodesWithText` guard already uses.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Документы согласий").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Документы согласий").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theConsentDocumentsRowIsHiddenButAdministrationStays() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(emptySet()),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()

        // No `site:configure` - «Документы согласий» is gone, but Администрирование itself stays: its
        // «Операторы и роли» row is unconditional (this file's own doc comment states why this case cannot
        // mirror `CannedResponsesGatingTest`'s/`TagsGatingTest`'s own "the whole section disappears"
        // assertion). «Тариф и оплата» is gone too now (`26-301`) - `BillingGatingTest` proves that row's
        // own with/without-permission cases.
        composeTestRule.onNodeWithText("Документы согласий").assertDoesNotExist()
        composeTestRule.onNodeWithText("Администрирование", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Операторы и роли").assertExists()
    }

    // Deliberately no "opening the row shows the real screen" case here - the identical boundary
    // `CannedResponsesGatingTest`'s own doc comment states: opening the row reaches
    // [ConsentDocumentsRoute]'s own `hiltViewModel()`, which needs a real Hilt component this suite's
    // plain `ComponentActivity` does not provide.
}
