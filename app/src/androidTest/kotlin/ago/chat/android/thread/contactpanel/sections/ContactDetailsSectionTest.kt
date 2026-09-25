package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.thread.contactpanel.ContactDetailsSectionState
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-148`: the КОНТАКТНЫЕ ДАННЫЕ section's own two promises, proven at the Compose level with **no Hilt
 * in play** — the section is a stateless composable driven by a hand-built
 * [ContactDetailsSectionState] and plain callbacks, the identical "drive the stateless composable
 * directly" split [ago.chat.android.thread.contactpanel.ContactDetailPanelTest] already establishes for
 * the shell:
 *
 * 1. it renders the visitor's fields (name plain, phone/email with «Показать» while masked), and
 * 2. tapping «Показать» drives a reveal that unmasks that one row in place.
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class ContactDetailsSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersNamePlainAndAMaskedPhoneWithTheRevealControl() {
        composeTestRule.setContent {
            ContactDetailsSection(
                state =
                    ContactDetailsSectionState.Loaded(
                        details =
                            listOf(
                                ContactDetail(id = "n1", kind = "Name", value = "Аня", masked = false),
                                ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true),
                            ),
                    ),
                onReveal = {},
                onRetry = {},
            )
        }

        // The name is plain display text - present, and with no reveal control of its own.
        composeTestRule.onNodeWithText("Аня").assertIsDisplayed()
        composeTestRule.onNodeWithText("+7 •• ••").assertIsDisplayed()
        // A masked phone offers «Показать».
        composeTestRule.onNodeWithText("Показать").assertIsDisplayed()
    }

    @Test
    fun tappingShowRevealsThatRowInPlace() {
        composeTestRule.setContent {
            // A tiny stateful host standing in for the view model: the tap swaps the masked row for the
            // server's unmasked one, exactly as `ContactPanelViewModel.revealContactDetail` does - so this
            // test exercises the section's masked→unmasked rendering without any Hilt/VM machinery.
            var details by
                remember {
                    mutableStateOf(
                        listOf(ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true)),
                    )
                }
            RevealHost(
                details = details,
                onReveal = { id ->
                    details = details.map { if (it.id == id) it.copy(value = "+7 900 111 22 33", masked = false) else it }
                },
            )
        }

        composeTestRule.onNodeWithText("Показать").assertIsDisplayed()
        composeTestRule.onNodeWithText("Показать").performClick()
        composeTestRule.waitForIdle()

        // The masked value and its control are gone; the unmasked value is on screen.
        composeTestRule.onNodeWithText("+7 900 111 22 33").assertIsDisplayed()
        composeTestRule.onNodeWithText("Показать").assertDoesNotExist()
    }

    @Composable
    private fun RevealHost(
        details: List<ContactDetail>,
        onReveal: (String) -> Unit,
    ) {
        ContactDetailsSection(
            state = ContactDetailsSectionState.Loaded(details = details),
            onReveal = onReveal,
            onRetry = {},
        )
    }
}
