package ago.chat.android.shell

import ago.chat.android.BuildConfig
import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.ui.theme.ThemeMode
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-17`: the stateless half of the Settings screen, driven directly with fixed state and no Hilt
 * component at all — the identical "route wires, screen renders, a test substitutes its own state"
 * split this project already applies to [ago.chat.android.conversations.ConversationListScreen] and to
 * [AppShellScreen] itself. What [SettingsViewModel]'s own report cannot prove without a real
 * `IdentityApi`/`OperatorHubEvents` — what actually renders for a given state — is what this file proves
 * instead; [SettingsViewModelTest] (a plain JVM test) proves the state transitions themselves.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val siteA = Tenancy("11111111-1111-1111-1111-111111111111", "Кофейня на Мира")
    private val siteB = Tenancy("22222222-2222-2222-2222-222222222222", "Ярмарка")

    @Test
    fun tappingATenancyCallsOnSwitchSiteWithThatSiteId() {
        var switchedTo: String? = null
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(listOf(siteA, siteB)),
                currentSiteId = siteA.siteId,
                switching = false,
                onSwitchSite = { switchedTo = it },
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(siteB.siteName).performClick()

        assertEquals(siteB.siteId, switchedTo)
    }

    /**
     * `26-17`'s own "pick one and say why": a single-tenancy identity has nothing to switch *to*, so
     * the switcher is not drawn at all — never shown-disabled, never shown-read-only.
     */
    @Test
    fun theSiteSectionIsAbsentForASingleTenancyIdentity() {
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(listOf(siteA)),
                currentSiteId = siteA.siteId,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Текущий сайт").assertDoesNotExist()
        composeTestRule.onNodeWithText(siteA.siteName).assertDoesNotExist()
    }

    /** The same absence, for the two states this screen cannot tell "one site" apart from: still
     * loading, and the fetch failed. Showing a switcher that might be lying about the real count is
     * worse than showing none until the real answer arrives — this screen's own doc comment states the
     * same judgement. */
    @Test
    fun theSiteSectionIsAbsentWhileTenanciesHaveNotAnsweredYet() {
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Unanswered(ProbeFailure.Transport("timed out")),
                currentSiteId = null,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Текущий сайт").assertDoesNotExist()
    }

    @Test
    fun selectingAThemeOptionCallsOnThemeModeSelected() {
        var selected: ThemeMode? = null
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = { selected = it },
                tenancies = TenancyListing.Known(emptyList()),
                currentSiteId = null,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Тёмная").performClick()

        assertEquals(ThemeMode.Dark, selected)
    }

    @Test
    fun aboutShowsTheRealBuildTypeAndVersionName() {
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(emptyList()),
                currentSiteId = null,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(BuildConfig.BUILD_TYPE).assertExists()
        composeTestRule.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
    }

    @Test
    fun tappingSignOutCallsOnSignOut() {
        var signedOut = false
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(emptyList()),
                currentSiteId = null,
                switching = false,
                onSwitchSite = {},
                onSignOut = { signedOut = true },
                onBack = {},
            )
        }

        // `26-69`: on a real device (never on CI's emulator) this `LazyColumn`'s last item - the one
        // this test targets - was found by `onNodeWithText` in a state where `performClick()` ran but
        // `onSignOut` never fired; scrolling the node into a stable, fully-measured position first is
        // the standing fix for a `LazyColumn`-bottom-item click, real cause or not.
        composeTestRule.onNodeWithText("Выйти").performScrollTo().performClick()

        assertEquals(true, signedOut)
    }

    @Test
    fun backArrowCallsOnBack() {
        var backCalls = 0
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(emptyList()),
                currentSiteId = null,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = { backCalls++ },
            )
        }

        // `26-43`: the back control used to be found by its own literal "←" text; it is a real
        // vector `Icon` now, so the semantics tree carries no text at all for it - found by its
        // `contentDescription` instead ("Назад", `R.string.action_back`), the same hardcoded-literal
        // convention this file's own other `onNodeWithText` calls already use.
        composeTestRule.onNodeWithContentDescription("Назад").performClick()

        assertEquals(1, backCalls)
    }

    /**
     * `26-66`: both choice groups are `selectable(role = Role.RadioButton)` rows now, not bare
     * `selectable` nodes — without the role the merged node carries a selected state and nothing
     * telling a screen reader what kind of control it is.
     */
    @Test
    fun eachThemeRowIsARadioButtonWithTheRightSelectionState() {
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.Dark,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(emptyList()),
                currentSiteId = null,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Системная").assert(hasRadioButtonRole).assertIsNotSelected()
        composeTestRule.onNodeWithText("Светлая").assert(hasRadioButtonRole).assertIsNotSelected()
        composeTestRule.onNodeWithText("Тёмная").assert(hasRadioButtonRole).assertIsSelected()
    }

    /**
     * The site group, same shape: role, selection, and — while a switch is in flight — the disabled
     * announcement `26-66` says should fall out of `selectable(enabled = …)` once the role is set.
     */
    @Test
    fun eachSiteRowIsARadioButtonWithTheRightSelectionAndDisabledState() {
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(listOf(siteA, siteB)),
                currentSiteId = siteA.siteId,
                switching = true,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(siteA.siteName)
            .assert(hasRadioButtonRole)
            .assertIsSelected()
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(siteB.siteName)
            .assert(hasRadioButtonRole)
            .assertIsNotSelected()
            .assertIsNotEnabled()
    }

    /**
     * `26-66` scope item 3: the site's short identifier — eight monospace hex characters meant to be
     * read with the eyes, per `IdentifierText`'s own doc comment — must not be announced as part of the
     * row's name. `clearAndSetSemantics {}` on that `Text` is what this asserts: with it excluded, the
     * id is simply not in the semantics tree to be found by its text.
     */
    @Test
    fun theSiteIdentifierIsNotPartOfTheRowsAnnouncedName() {
        composeTestRule.setContent {
            SettingsScreen(
                themeMode = ThemeMode.System,
                onThemeModeSelected = {},
                tenancies = TenancyListing.Known(listOf(siteA, siteB)),
                currentSiteId = siteA.siteId,
                switching = false,
                onSwitchSite = {},
                onSignOut = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(siteA.siteId.take(8)).assertDoesNotExist()
        composeTestRule.onNodeWithText(siteB.siteId.take(8)).assertDoesNotExist()
    }

    private companion object {
        val hasRadioButtonRole: SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    }
}
