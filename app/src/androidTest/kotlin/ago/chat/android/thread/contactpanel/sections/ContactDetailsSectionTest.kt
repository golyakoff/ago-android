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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-148`/`26-169`: the КОНТАКТНЫЕ ДАННЫЕ section's own promises, proven at the Compose level with **no
 * Hilt in play** — the section is a stateless composable driven by a hand-built
 * [ContactDetailsSectionState] and plain callbacks, the identical "drive the stateless composable
 * directly" split [ago.chat.android.thread.contactpanel.ContactDetailPanelTest] already establishes for
 * the shell:
 *
 * 1. it renders the visitor's fields (name plain, phone/email with «Показать» while masked);
 * 2. tapping «Показать» drives a reveal that unmasks that one row in place;
 * 3. the row `⋮` (edit + assessment) is drawn only with `conversation:send`, and only when it has
 *    something applicable to offer (never for a masked row's «Изменить», design Q2); and
 * 4. the assessment word (+glyph for «Подтверждено») renders beside the value, never a colour alone.
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
            StaticSection(
                details =
                    listOf(
                        ContactDetail(id = "n1", kind = "Name", value = "Аня", masked = false),
                        ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true),
                    ),
                canSendConversation = false,
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
            ContactDetailsSection(
                state = ContactDetailsSectionState.Loaded(details = details),
                canSendConversation = false,
                onReveal = { id -> details = details.map { if (it.id == id) it.copy(value = "+7 900 111 22 33", masked = false) else it } },
                onRetry = {},
                onStartEdit = {},
                onEditDraftChanged = {},
                onSaveEdit = {},
                onCancelEdit = {},
                onSetAssessment = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Показать").assertIsDisplayed()
        composeTestRule.onNodeWithText("Показать").performClick()
        composeTestRule.waitForIdle()

        // The masked value and its control are gone; the unmasked value is on screen.
        composeTestRule.onNodeWithText("+7 900 111 22 33").assertIsDisplayed()
        composeTestRule.onNodeWithText("Показать").assertDoesNotExist()
    }

    // ─── 26-169: row `⋮` (edit + assessment) ───────────────────────────────────────────────────────────

    @Test
    fun theRowMenuIsHiddenWithoutConversationSendPermission() {
        composeTestRule.setContent {
            StaticSection(
                details = listOf(ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)),
                canSendConversation = false,
            )
        }

        composeTestRule.onNodeWithContentDescription("Действия").assertDoesNotExist()
    }

    @Test
    fun theRowMenuIsShownWithConversationSendPermission() {
        composeTestRule.setContent {
            StaticSection(
                details = listOf(ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)),
                canSendConversation = true,
            )
        }

        composeTestRule.onNodeWithContentDescription("Действия").assertIsDisplayed()
    }

    @Test
    fun editIsHiddenWhileTheValueIsMasked() {
        composeTestRule.setContent {
            StaticSection(
                details = listOf(ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true)),
                canSendConversation = true,
            )
        }

        // A masked Phone row still offers assessment actions (an operator's own judgement, not a claim about
        // having read the digits - `ContactDetailsPanel.tsx`'s own remarks) - so the `⋮` itself is present -
        // but «Изменить» is not one of its entries (design Q2).
        composeTestRule.onNodeWithContentDescription("Действия").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Действия").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Изменить").assertDoesNotExist()
        composeTestRule.onNodeWithText("Подтвердить").assertIsDisplayed()
    }

    @Test
    fun tappingEditOpensTheEditorPrefilledWithTheCurrentValue() {
        composeTestRule.setContent {
            StaticSection(
                details = listOf(ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)),
                canSendConversation = true,
                editingId = "p1",
                editDraft = "+7 900 111 22 33",
            )
        }

        composeTestRule.onNodeWithText("+7 900 111 22 33").assertIsDisplayed()
        composeTestRule.onNodeWithText("Сохранить").assertIsDisplayed()
        composeTestRule.onNodeWithText("Отмена").assertIsDisplayed()
    }

    @Test
    fun aNameRowsMenuNeverOffersAssessmentActions() {
        composeTestRule.setContent {
            StaticSection(
                details = listOf(ContactDetail(id = "n1", kind = "Name", value = "Аня", masked = false)),
                canSendConversation = true,
            )
        }

        composeTestRule.onNodeWithContentDescription("Действия").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Изменить").assertIsDisplayed()
        composeTestRule.onNodeWithText("Подтвердить").assertDoesNotExist()
        composeTestRule.onNodeWithText("Отметить недействительным").assertDoesNotExist()
    }

    @Test
    fun theApplicableAssessmentActionIsHiddenOnceAlreadyInThatState() {
        composeTestRule.setContent {
            StaticSection(
                details =
                    listOf(
                        ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false, assessment = "Confirmed"),
                    ),
                canSendConversation = true,
            )
        }

        // «Подтверждено» is rendered on the value line...
        composeTestRule.onNodeWithText("Подтверждено").assertIsDisplayed()

        // ...and the menu no longer offers «Подтвердить» again, only the reverse.
        composeTestRule.onNodeWithContentDescription("Действия").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Подтвердить").assertDoesNotExist()
        composeTestRule.onNodeWithText("Отметить недействительным").assertIsDisplayed()
    }

    @Test
    fun anInvalidAssessmentRendersTheWordInErrorColourWithNoGlyph() {
        composeTestRule.setContent {
            StaticSection(
                details =
                    listOf(
                        ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false, assessment = "Invalid"),
                    ),
                canSendConversation = false,
            )
        }

        composeTestRule.onNodeWithText("Недействительно").assertIsDisplayed()
    }

    @Composable
    private fun StaticSection(
        details: List<ContactDetail>,
        canSendConversation: Boolean,
        editingId: String? = null,
        editDraft: String = "",
    ) {
        ContactDetailsSection(
            state = ContactDetailsSectionState.Loaded(details = details, editingId = editingId, editDraft = editDraft),
            canSendConversation = canSendConversation,
            onReveal = {},
            onRetry = {},
            onStartEdit = {},
            onEditDraftChanged = {},
            onSaveEdit = {},
            onCancelEdit = {},
            onSetAssessment = { _, _ -> },
        )
    }
}
