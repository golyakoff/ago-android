package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.notes.AddNoteResult
import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.core.domain.notes.ConversationNotesApi
import ago.chat.android.core.domain.notes.ConversationNotesResult
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.ConversationTagsApi
import ago.chat.android.core.domain.tags.ConversationTagsResult
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagActionResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-147`/`26-148`: the panel view model's async arms — the header's one visitor-summary read
 * (`26-147`) and the КОНТАКТНЫЕ ДАННЫЕ section's list + per-row reveal (`26-148`), their UI arms, and the
 * open/retry/reveal sequencing. The identical `StandardTestDispatcher`/`Dispatchers.setMain` shape the
 * sibling one-shot view-model tests already establish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPanelViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        summaryApi: VisitorSummaryApi = FakeVisitorSummaryApi(),
        contactDetailsApi: ContactDetailsApi = FakeContactDetailsApi(),
        conversationTagsApi: ConversationTagsApi = FakeConversationTagsApi(),
        conversationNotesApi: ConversationNotesApi = FakeConversationNotesApi(),
    ) = ContactPanelViewModel(
        visitorSummaryApi = summaryApi,
        contactDetailsApi = contactDetailsApi,
        conversationTagsApi = conversationTagsApi,
        conversationNotesApi = conversationNotesApi,
        ioDispatcher = dispatcher,
    )

    @Test
    fun `open shows Loading before the summary comes back`() =
        runTest(dispatcher) {
            val viewModel = viewModel(summaryApi = FakeVisitorSummaryApi(hang = true))

            viewModel.open("c1")
            dispatcher.scheduler.runCurrent()

            assertEquals(HeaderSummaryState.Loading, viewModel.state.value.summary)
        }

    @Test
    fun `a loaded summary becomes the Loaded header arm`() =
        runTest(dispatcher) {
            val summary = VisitorSummary(firstSeenAt = Instant.parse("2026-03-14T06:30:00Z"), conversationCount = 7)
            val viewModel = viewModel(summaryApi = FakeVisitorSummaryApi(result = VisitorSummaryResult.Loaded(summary)))

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(HeaderSummaryState.Loaded(summary), viewModel.state.value.summary)
        }

    @Test
    fun `a failed read becomes the Failed arm, and retry asks again`() =
        runTest(dispatcher) {
            val api = FakeVisitorSummaryApi(result = VisitorSummaryResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(summaryApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(HeaderSummaryState.Failed(NetworkFailure.NoConnection), viewModel.state.value.summary)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(2, api.calls)
        }

    @Test
    fun `re-opening the same conversation does not re-read`() =
        runTest(dispatcher) {
            val summary = VisitorSummary(firstSeenAt = null, conversationCount = 1)
            val summaryApi = FakeVisitorSummaryApi(result = VisitorSummaryResult.Loaded(summary))
            val contactDetailsApi = FakeContactDetailsApi()
            val viewModel = viewModel(summaryApi = summaryApi, contactDetailsApi = contactDetailsApi)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(1, summaryApi.calls)
            assertEquals(1, contactDetailsApi.listCalls)
        }

    // ─── 26-148: КОНТАКТНЫЕ ДАННЫЕ section ────────────────────────────────────────────────────────────

    @Test
    fun `open loads the contact-details rows into the Loaded arm`() =
        runTest(dispatcher) {
            val rows =
                listOf(
                    ContactDetail(id = "n1", kind = "Name", value = "Аня", masked = false),
                    ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true),
                )
            val viewModel = viewModel(contactDetailsApi = FakeContactDetailsApi(listResult = ContactDetailsResult.Loaded(rows)))

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(
                ContactDetailsSectionState.Loaded(rows),
                viewModel.state.value.contactDetails,
            )
        }

    @Test
    fun `a failed contact-details read becomes the Failed arm, and its retry asks again`() =
        runTest(dispatcher) {
            val api = FakeContactDetailsApi(listResult = ContactDetailsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(
                ContactDetailsSectionState.Failed(NetworkFailure.NoConnection),
                viewModel.state.value.contactDetails,
            )

            viewModel.retryContactDetails()
            advanceUntilIdle()

            assertEquals(2, api.listCalls)
        }

    @Test
    fun `reveal replaces the masked row in place with the server's unmasked value`() =
        runTest(dispatcher) {
            val masked = ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true)
            val unmasked = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(masked)),
                    revealResult = RevealContactDetailResult.Revealed(unmasked),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.revealContactDetail("p1")
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(listOf(unmasked), loaded.details)
            assertTrue(loaded.revealingIds.isEmpty())
            assertTrue(loaded.revealErrors.isEmpty())
        }

    @Test
    fun `a refused reveal keeps the masked value and surfaces the server detail on that row`() =
        runTest(dispatcher) {
            val masked = ContactDetail(id = "p1", kind = "Phone", value = "+7 •• ••", masked = true)
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(masked)),
                    revealResult = RevealContactDetailResult.Refused("Not entitled"),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.revealContactDetail("p1")
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(listOf(masked), loaded.details)
            assertEquals(RowRevealError.Refused("Not entitled"), loaded.revealErrors["p1"])
        }

    // ─── 26-149: tags section ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `open loads the applied tags and the site vocabulary into the Loaded arm`() =
        runTest(dispatcher) {
            val applied = listOf(ConversationTag(id = "t1", name = "VIP", createdAt = "2026-01-01T00:00:00Z", source = "Operator"))
            val vocabulary =
                listOf(
                    Tag(id = "t1", name = "VIP", createdAt = "2026-01-01T00:00:00Z"),
                    Tag(id = "t2", name = "Refund", createdAt = "2026-01-02T00:00:00Z"),
                )
            val api =
                FakeConversationTagsApi(
                    conversationTagsResult = ConversationTagsResult.Loaded(applied),
                    siteTagsResult = TagVocabularyResult.Loaded(vocabulary),
                )
            val viewModel = viewModel(conversationTagsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(
                TagsSectionState.Loaded(applied = applied, vocabulary = vocabulary),
                viewModel.state.value.tags,
            )
        }

    @Test
    fun `a failed conversation-tags read becomes the Failed arm, and its retry asks again`() =
        runTest(dispatcher) {
            val api = FakeConversationTagsApi(conversationTagsResult = ConversationTagsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(conversationTagsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(TagsSectionState.Failed(NetworkFailure.NoConnection), viewModel.state.value.tags)

            viewModel.retryTags()
            advanceUntilIdle()

            assertEquals(2, api.conversationTagsCalls)
        }

    @Test
    fun `a failed vocabulary read degrades to an empty vocabulary without failing the section`() =
        runTest(dispatcher) {
            val applied = listOf(ConversationTag(id = "t1", name = "VIP", createdAt = "2026-01-01T00:00:00Z", source = "Operator"))
            val api =
                FakeConversationTagsApi(
                    conversationTagsResult = ConversationTagsResult.Loaded(applied),
                    siteTagsResult = TagVocabularyResult.Failed(NetworkFailure.Unexpected),
                )
            val viewModel = viewModel(conversationTagsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(
                TagsSectionState.Loaded(applied = applied, vocabulary = emptyList()),
                viewModel.state.value.tags,
            )
        }

    @Test
    fun `applyTag adds the vocabulary tag to the applied set on success`() =
        runTest(dispatcher) {
            val vocabulary =
                listOf(
                    Tag(id = "t1", name = "VIP", createdAt = "2026-01-01T00:00:00Z"),
                    Tag(id = "t2", name = "Refund", createdAt = "2026-01-02T00:00:00Z"),
                )
            val api =
                FakeConversationTagsApi(
                    conversationTagsResult = ConversationTagsResult.Loaded(emptyList()),
                    siteTagsResult = TagVocabularyResult.Loaded(vocabulary),
                    applyResult = TagActionResult.Succeeded,
                )
            val viewModel = viewModel(conversationTagsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.applyTag("t2")
            advanceUntilIdle()

            val loaded = viewModel.state.value.tags as TagsSectionState.Loaded
            assertEquals(
                listOf(ConversationTag(id = "t2", name = "Refund", createdAt = "2026-01-02T00:00:00Z", source = "Operator")),
                loaded.applied,
            )
            assertTrue(loaded.pendingTagIds.isEmpty())
            assertEquals(null, loaded.actionError)
        }

    @Test
    fun `removeTag drops the tag from the applied set on success`() =
        runTest(dispatcher) {
            val applied =
                listOf(
                    ConversationTag(id = "t1", name = "VIP", createdAt = "2026-01-01T00:00:00Z", source = "Operator"),
                    ConversationTag(id = "t2", name = "Refund", createdAt = "2026-01-02T00:00:00Z", source = "Operator"),
                )
            val api =
                FakeConversationTagsApi(
                    conversationTagsResult = ConversationTagsResult.Loaded(applied),
                    siteTagsResult = TagVocabularyResult.Loaded(emptyList()),
                    removeResult = TagActionResult.Succeeded,
                )
            val viewModel = viewModel(conversationTagsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.removeTag("t1")
            advanceUntilIdle()

            val loaded = viewModel.state.value.tags as TagsSectionState.Loaded
            assertEquals(listOf(applied[1]), loaded.applied)
        }

    @Test
    fun `a refused apply keeps the applied set and surfaces the server detail`() =
        runTest(dispatcher) {
            val vocabulary = listOf(Tag(id = "t2", name = "Refund", createdAt = "2026-01-02T00:00:00Z"))
            val api =
                FakeConversationTagsApi(
                    conversationTagsResult = ConversationTagsResult.Loaded(emptyList()),
                    siteTagsResult = TagVocabularyResult.Loaded(vocabulary),
                    applyResult = TagActionResult.Refused("Not entitled"),
                )
            val viewModel = viewModel(conversationTagsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.applyTag("t2")
            advanceUntilIdle()

            val loaded = viewModel.state.value.tags as TagsSectionState.Loaded
            assertTrue(loaded.applied.isEmpty())
            assertEquals(TagActionError.Refused("Not entitled"), loaded.actionError)
        }

    // ─── 26-150: notes section ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `open loads the notes list into the Loaded arm`() =
        runTest(dispatcher) {
            val notes = listOf(ConversationNote(id = "n1", authorId = "op1", body = "Позвонить завтра", createdAt = "2026-03-14T09:00:00Z"))
            val viewModel = viewModel(conversationNotesApi = FakeConversationNotesApi(listResult = ConversationNotesResult.Loaded(notes)))

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(NotesSectionState.Loaded(notes = notes), viewModel.state.value.notes)
        }

    @Test
    fun `a failed notes read becomes the Failed arm, and its retry asks again`() =
        runTest(dispatcher) {
            val api = FakeConversationNotesApi(listResult = ConversationNotesResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(conversationNotesApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(NotesSectionState.Failed(NetworkFailure.NoConnection), viewModel.state.value.notes)

            viewModel.retryNotes()
            advanceUntilIdle()

            assertEquals(2, api.listCalls)
        }

    @Test
    fun `onNoteDraftChanged updates the composer draft`() =
        runTest(dispatcher) {
            val api = FakeConversationNotesApi(listResult = ConversationNotesResult.Loaded(emptyList()))
            val viewModel = viewModel(conversationNotesApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.onNoteDraftChanged("Позвонить завтра")

            val loaded = viewModel.state.value.notes as NotesSectionState.Loaded
            assertEquals("Позвонить завтра", loaded.draft)
        }

    @Test
    fun `addNote appends the server's created row and clears the draft on success`() =
        runTest(dispatcher) {
            val created = ConversationNote(id = "n1", authorId = "op1", body = "Позвонить завтра", createdAt = "2026-03-14T09:00:00Z")
            val api =
                FakeConversationNotesApi(
                    listResult = ConversationNotesResult.Loaded(emptyList()),
                    addResult = AddNoteResult.Added(created),
                )
            val viewModel = viewModel(conversationNotesApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.onNoteDraftChanged("Позвонить завтра")
            viewModel.addNote()
            advanceUntilIdle()

            val loaded = viewModel.state.value.notes as NotesSectionState.Loaded
            assertEquals(listOf(created), loaded.notes)
            assertEquals("", loaded.draft)
            assertTrue(!loaded.addingNote)
            assertEquals(null, loaded.addNoteError)
        }

    @Test
    fun `a refused add keeps the notes list and the draft, and surfaces the server detail`() =
        runTest(dispatcher) {
            val api =
                FakeConversationNotesApi(
                    listResult = ConversationNotesResult.Loaded(emptyList()),
                    addResult = AddNoteResult.Refused("Not entitled"),
                )
            val viewModel = viewModel(conversationNotesApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.onNoteDraftChanged("Позвонить завтра")
            viewModel.addNote()
            advanceUntilIdle()

            val loaded = viewModel.state.value.notes as NotesSectionState.Loaded
            assertTrue(loaded.notes.isEmpty())
            assertEquals("Позвонить завтра", loaded.draft)
            assertEquals(AddNoteError.Refused("Not entitled"), loaded.addNoteError)
        }

    @Test
    fun `addNote is a no-op for a blank draft`() =
        runTest(dispatcher) {
            val api = FakeConversationNotesApi(listResult = ConversationNotesResult.Loaded(emptyList()))
            val viewModel = viewModel(conversationNotesApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.onNoteDraftChanged("   ")
            viewModel.addNote()
            advanceUntilIdle()

            assertEquals(0, api.addCalls)
        }

    private class FakeVisitorSummaryApi(
        private val result: VisitorSummaryResult = VisitorSummaryResult.Failed(NetworkFailure.Unexpected),
        private val hang: Boolean = false,
    ) : VisitorSummaryApi {
        var calls = 0

        override suspend fun fetchVisitorSummary(conversationId: String): VisitorSummaryResult {
            calls++
            if (hang) awaitCancellation()
            return result
        }
    }

    private class FakeContactDetailsApi(
        private val listResult: ContactDetailsResult = ContactDetailsResult.Loaded(emptyList()),
        private val revealResult: RevealContactDetailResult = RevealContactDetailResult.Failed(NetworkFailure.Unexpected),
    ) : ContactDetailsApi {
        var listCalls = 0
        var revealCalls = 0

        override suspend fun fetchContactDetails(conversationId: String): ContactDetailsResult {
            listCalls++
            return listResult
        }

        override suspend fun revealContactDetail(
            conversationId: String,
            contactDetailId: String,
        ): RevealContactDetailResult {
            revealCalls++
            return revealResult
        }
    }

    private class FakeConversationTagsApi(
        private val conversationTagsResult: ConversationTagsResult = ConversationTagsResult.Loaded(emptyList()),
        private val siteTagsResult: TagVocabularyResult = TagVocabularyResult.Loaded(emptyList()),
        private val applyResult: TagActionResult = TagActionResult.Failed(NetworkFailure.Unexpected),
        private val removeResult: TagActionResult = TagActionResult.Failed(NetworkFailure.Unexpected),
    ) : ConversationTagsApi {
        var conversationTagsCalls = 0
        var siteTagsCalls = 0
        var applyCalls = 0
        var removeCalls = 0

        override suspend fun fetchSiteTags(): TagVocabularyResult {
            siteTagsCalls++
            return siteTagsResult
        }

        override suspend fun fetchConversationTags(conversationId: String): ConversationTagsResult {
            conversationTagsCalls++
            return conversationTagsResult
        }

        override suspend fun applyTag(
            conversationId: String,
            tagId: String,
        ): TagActionResult {
            applyCalls++
            return applyResult
        }

        override suspend fun removeTag(
            conversationId: String,
            tagId: String,
        ): TagActionResult {
            removeCalls++
            return removeResult
        }
    }

    private class FakeConversationNotesApi(
        private val listResult: ConversationNotesResult = ConversationNotesResult.Loaded(emptyList()),
        private val addResult: AddNoteResult = AddNoteResult.Failed(NetworkFailure.Unexpected),
    ) : ConversationNotesApi {
        var listCalls = 0
        var addCalls = 0

        override suspend fun fetchNotes(conversationId: String): ConversationNotesResult {
            listCalls++
            return listResult
        }

        override suspend fun addNote(
            conversationId: String,
            body: String,
        ): AddNoteResult {
            addCalls++
            return addResult
        }
    }
}
