package ago.chat.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-246`: a pure-Kotlin guard over [buildMoreRows]' own row set and order — the part of the Ещё
 * list-of-lists that the instrumented gating tests (`AiSuggestionsGatingTest`, `ConsentDocumentsGatingTest`,
 * `DeviceStorageDisclosureTest`) exercise through a real `LazyColumn`, restated here as an assertion that
 * runs without a device. This is the layer that proves the *product* code is right independently of any
 * scroll/settle behaviour in the instrumented harness: the AI row exists under the `site:configure` gate,
 * in the design-doc order, and the two deep Администрирование rows the gating tests scroll to are still
 * present and unmoved.
 */
class BuildMoreRowsTest {
    @Test
    fun `with site configure the ai-suggestions row sits between canned responses and offline auto-reply`() {
        val ids = buildMoreRows(canConfigureSite = true).map { it.id }

        val quickReplies = ids.indexOf(AUTOMATION_QUICK_REPLIES_ROW_ID)
        val aiSuggestions = ids.indexOf(AUTOMATION_AI_SUGGESTIONS_ROW_ID)
        val afterHours = ids.indexOf(AUTOMATION_AFTER_HOURS_ROW_ID)

        assertTrue("the ai-suggestions row is present with site:configure", aiSuggestions >= 0)
        // `docs/design/tenant-canned-tags-android.md` §3.2 order: Готовые ответы · ИИ-подсказки · Автоответ
        // вне смены · База знаний · Метки.
        assertTrue("ai-suggestions comes after canned responses", quickReplies in 0 until aiSuggestions)
        assertTrue("ai-suggestions comes before offline auto-reply", aiSuggestions < afterHours)
    }

    @Test
    fun `the ai-suggestions row is a site configure gated automation row`() {
        val row = buildMoreRows(canConfigureSite = true).single { it.id == AUTOMATION_AI_SUGGESTIONS_ROW_ID }

        assertEquals(MoreSectionId.Automation, row.section)
        // Gated exactly like every other real Автоматизация row: absent without `site:configure`.
        assertFalse(
            "ai-suggestions must be hidden without site:configure",
            buildMoreRows(canConfigureSite = false).any { it.id == AUTOMATION_AI_SUGGESTIONS_ROW_ID },
        )
    }

    @Test
    fun `the deep administration rows the gating tests scroll to are still present and after the ai row`() {
        val ids = buildMoreRows(canConfigureSite = true).map { it.id }

        // The two rows whose instrumented gating tests scroll the full list to reach them; the AI row
        // lengthens that list, so this pins down that they remain present (and below the AI row) - the
        // product-side counterpart to the `waitForIdle()` those instrumented tests now use after scrolling.
        val documents = ids.indexOf(ADMINISTRATION_DOCUMENTS_ROW_ID)
        val reference = ids.indexOf(ADMINISTRATION_REFERENCE_ROW_ID)
        val aiSuggestions = ids.indexOf(AUTOMATION_AI_SUGGESTIONS_ROW_ID)

        assertTrue("документы согласий row present", documents >= 0)
        assertTrue("справка row present", reference >= 0)
        assertTrue("both administration rows are below the automation ai row", documents > aiSuggestions && reference > aiSuggestions)
    }
}
