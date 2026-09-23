package ago.chat.android.shell

import ago.chat.android.BuildConfig
import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.ui.theme.ThemeMode
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        composeTestRule.onNodeWithText("Выйти").performClick()

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
}
