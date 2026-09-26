package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailWriteResult
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.conversationactions.ConversationActionResult
import ago.chat.android.core.domain.conversationactions.ConversationActionsApi
import ago.chat.android.core.domain.conversations.AllConversationsResult
import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.ErasureResult
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.notes.AddNoteResult
import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.core.domain.notes.ConversationNotesApi
import ago.chat.android.core.domain.notes.ConversationNotesResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionApi
import ago.chat.android.core.domain.restrictions.VisitorRestrictionStatusResult
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.ConversationTagsApi
import ago.chat.android.core.domain.tags.ConversationTagsResult
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagActionResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryApi
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryConversation
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryPage
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryResult
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.HistoryPage
import ago.chat.android.core.network.realtime.MessageDeliveredDto
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.TeamHistoryPage
import ago.chat.android.core.network.realtime.TeamMessageDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        visitorHistoryApi: VisitorHistoryApi = FakeVisitorHistoryApi(),
        hubEvents: OperatorHubEvents = FakeOperatorHubEvents(),
        conversationsApi: ConversationsApi = FakeConversationsApi(),
        conversationActionsApi: ConversationActionsApi = FakeConversationActionsApi(),
        visitorRestrictionApi: VisitorRestrictionApi = FakeVisitorRestrictionApi(),
    ) = ContactPanelViewModel(
        visitorSummaryApi = summaryApi,
        contactDetailsApi = contactDetailsApi,
        conversationTagsApi = conversationTagsApi,
        conversationNotesApi = conversationNotesApi,
        visitorHistoryApi = visitorHistoryApi,
        hubEvents = hubEvents,
        conversationsApi = conversationsApi,
        conversationActionsApi = conversationActionsApi,
        visitorRestrictionApi = visitorRestrictionApi,
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
            assertTrue(loaded.pendingIds.isEmpty())
            assertTrue(loaded.rowErrors.isEmpty())
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
            assertEquals(RowActionError.Refused("Not entitled"), loaded.rowErrors["p1"])
        }

    // ─── 26-169: КОНТАКТНЫЕ ДАННЫЕ edit + assessment ───────────────────────────────────────────────

    @Test
    fun `startEditContactDetail opens the editor prefilled with the row's current value`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val viewModel = viewModel(contactDetailsApi = FakeContactDetailsApi(listResult = ContactDetailsResult.Loaded(listOf(row))))

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.startEditContactDetail("p1")

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals("p1", loaded.editingId)
            assertEquals("+7 900 111 22 33", loaded.editDraft)
        }

    @Test
    fun `opening a second row's editor discards the first row's draft`() =
        runTest(dispatcher) {
            val rows =
                listOf(
                    ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false),
                    ContactDetail(id = "e1", kind = "Email", value = "a@example.com", masked = false),
                )
            val viewModel = viewModel(contactDetailsApi = FakeContactDetailsApi(listResult = ContactDetailsResult.Loaded(rows)))

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.startEditContactDetail("p1")
            viewModel.onEditContactDetailDraftChanged("+7 999 000 00 00")
            viewModel.startEditContactDetail("e1")

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals("e1", loaded.editingId)
            assertEquals("a@example.com", loaded.editDraft)
        }

    @Test
    fun `cancelEditContactDetail closes the editor without sending anything`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val api = FakeContactDetailsApi(listResult = ContactDetailsResult.Loaded(listOf(row)))
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.startEditContactDetail("p1")
            viewModel.onEditContactDetailDraftChanged("+7 999 000 00 00")
            viewModel.cancelEditContactDetail()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(null, loaded.editingId)
            assertEquals("", loaded.editDraft)
            assertEquals(0, api.editCalls)
        }

    @Test
    fun `saveEditContactDetail replaces the row in place and closes the editor on success`() =
        runTest(dispatcher) {
            val original =
                ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false, assessment = "Confirmed")
            val edited = ContactDetail(id = "p1", kind = "Phone", value = "+7 999 000 00 00", masked = false)
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(original)),
                    editResult = ContactDetailWriteResult.Updated(edited),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.startEditContactDetail("p1")
            viewModel.onEditContactDetailDraftChanged("+7 999 000 00 00")
            viewModel.saveEditContactDetail()
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            // The server's own answer replaces the row - assessment reset to `Unset` included, since
            // this method never fabricates the row itself.
            assertEquals(listOf(edited), loaded.details)
            assertEquals(null, loaded.editingId)
            assertEquals("", loaded.editDraft)
            assertTrue(loaded.pendingIds.isEmpty())
            assertEquals(1, api.editCalls)
        }

    @Test
    fun `a refused edit keeps the editor open with the draft, and surfaces the server detail`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(row)),
                    editResult = ContactDetailWriteResult.Refused("Слишком длинный номер."),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.startEditContactDetail("p1")
            viewModel.onEditContactDetailDraftChanged("+7 999 000 00 00 00 00")
            viewModel.saveEditContactDetail()
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(listOf(row), loaded.details)
            assertEquals("p1", loaded.editingId)
            assertEquals("+7 999 000 00 00 00 00", loaded.editDraft)
            assertEquals(RowActionError.Refused("Слишком длинный номер."), loaded.rowErrors["p1"])
        }

    @Test
    fun `saveEditContactDetail is a no-op for a blank draft`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val api = FakeContactDetailsApi(listResult = ContactDetailsResult.Loaded(listOf(row)))
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.startEditContactDetail("p1")
            viewModel.onEditContactDetailDraftChanged("   ")
            viewModel.saveEditContactDetail()
            advanceUntilIdle()

            assertEquals(0, api.editCalls)
        }

    @Test
    fun `setContactDetailAssessment replaces the row in place with the server's new assessment`() =
        runTest(dispatcher) {
            val unset = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val confirmed = unset.copy(assessment = "Confirmed")
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(unset)),
                    assessmentResult = ContactDetailWriteResult.Updated(confirmed),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.setContactDetailAssessment("p1", "Confirmed")
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(listOf(confirmed), loaded.details)
            assertTrue(loaded.pendingIds.isEmpty())
            assertEquals(1, api.assessmentCalls)
        }

    @Test
    fun `a refused assessment leaves the row untouched and surfaces the server detail`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "n1", kind = "Name", value = "Аня", masked = false)
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(row)),
                    assessmentResult = ContactDetailWriteResult.Refused("Имя нельзя оценивать."),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.setContactDetailAssessment("n1", "Confirmed")
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(listOf(row), loaded.details)
            assertEquals(RowActionError.Refused("Имя нельзя оценивать."), loaded.rowErrors["n1"])
        }

    @Test
    fun `a failed assessment write surfaces the assessment-specific generic reason`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val api =
                FakeContactDetailsApi(
                    listResult = ContactDetailsResult.Loaded(listOf(row)),
                    assessmentResult = ContactDetailWriteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(contactDetailsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.setContactDetailAssessment("p1", "Invalid")
            advanceUntilIdle()

            val loaded = viewModel.state.value.contactDetails as ContactDetailsSectionState.Loaded
            assertEquals(RowActionError.Failed.Assessment(NetworkFailure.NoConnection), loaded.rowErrors["p1"])
        }

    @Test
    fun `an assessment write already in flight for that row is a no-op`() =
        runTest(dispatcher) {
            val row = ContactDetail(id = "p1", kind = "Phone", value = "+7 900 111 22 33", masked = false)
            val hangingApi =
                object : ContactDetailsApi {
                    var calls = 0

                    override suspend fun fetchContactDetails(conversationId: String) = ContactDetailsResult.Loaded(listOf(row))

                    override suspend fun revealContactDetail(
                        conversationId: String,
                        contactDetailId: String,
                    ): RevealContactDetailResult = error("not used by this test")

                    override suspend fun editContactDetail(
                        conversationId: String,
                        contactDetailId: String,
                        value: String,
                    ): ContactDetailWriteResult = error("not used by this test")

                    override suspend fun setContactDetailAssessment(
                        conversationId: String,
                        contactDetailId: String,
                        assessment: String,
                    ): ContactDetailWriteResult {
                        calls++
                        awaitCancellation()
                    }
                }
            val viewModel = viewModel(contactDetailsApi = hangingApi)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.setContactDetailAssessment("p1", "Confirmed")
            dispatcher.scheduler.runCurrent()
            viewModel.setContactDetailAssessment("p1", "Confirmed")
            dispatcher.scheduler.runCurrent()

            assertEquals(1, hangingApi.calls)
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

    // ─── 26-151: past dialogs section ──────────────────────────────────────────────────────────────────

    @Test
    fun `open loads the past-dialogs list into the Loaded arm`() =
        runTest(dispatcher) {
            val conversations = listOf(historyConversation(id = "h1"))
            val api =
                FakeVisitorHistoryApi(
                    results = mutableListOf(VisitorHistoryResult.Loaded(VisitorHistoryPage(conversations, nextBeforeId = "h1"))),
                )
            val viewModel = viewModel(visitorHistoryApi = api)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(
                PastDialogsSectionState.Loaded(conversations = conversations, nextBeforeId = "h1"),
                viewModel.state.value.pastDialogs,
            )
        }

    @Test
    fun `a failed past-dialogs read becomes the Failed arm, and its retry asks again`() =
        runTest(dispatcher) {
            val api = FakeVisitorHistoryApi(results = mutableListOf(VisitorHistoryResult.Failed(NetworkFailure.NoConnection)))
            val viewModel = viewModel(visitorHistoryApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(PastDialogsSectionState.Failed(NetworkFailure.NoConnection), viewModel.state.value.pastDialogs)

            viewModel.retryPastDialogs()
            advanceUntilIdle()

            assertEquals(2, api.calls)
        }

    @Test
    fun `loadMorePastDialogs appends a further page and advances the cursor`() =
        runTest(dispatcher) {
            val first = historyConversation(id = "h1")
            val second = historyConversation(id = "h2")
            val api =
                FakeVisitorHistoryApi(
                    results =
                        mutableListOf(
                            VisitorHistoryResult.Loaded(VisitorHistoryPage(listOf(first), nextBeforeId = "h1")),
                            VisitorHistoryResult.Loaded(VisitorHistoryPage(listOf(second), nextBeforeId = null)),
                        ),
                )
            val viewModel = viewModel(visitorHistoryApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.loadMorePastDialogs()
            advanceUntilIdle()

            assertEquals(
                PastDialogsSectionState.Loaded(conversations = listOf(first, second), nextBeforeId = null),
                viewModel.state.value.pastDialogs,
            )
            assertEquals(listOf(null, "h1"), api.beforeIds)
        }

    @Test
    fun `loadMorePastDialogs is a no-op once the cursor is exhausted`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorHistoryApi(
                    results = mutableListOf(VisitorHistoryResult.Loaded(VisitorHistoryPage(emptyList(), nextBeforeId = null))),
                )
            val viewModel = viewModel(visitorHistoryApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.loadMorePastDialogs()
            advanceUntilIdle()

            assertEquals(1, api.calls)
        }

    @Test
    fun `openPastDialog fetches the transcript and lands it Loaded`() =
        runTest(dispatcher) {
            val conversation = historyConversation(id = "h1")
            val listApi =
                FakeVisitorHistoryApi(
                    results = mutableListOf(VisitorHistoryResult.Loaded(VisitorHistoryPage(listOf(conversation), nextBeforeId = null))),
                )
            val message = MessageDto(id = "m1", sequence = 1, body = "Привет", authorKind = "Visitor")
            val hub = FakeOperatorHubEvents().apply { historyResults += HistoryPage(messages = listOf(message), nextBeforeSequence = null) }
            val viewModel = viewModel(visitorHistoryApi = listApi, hubEvents = hub)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.openPastDialog("h1")
            advanceUntilIdle()

            val loaded = viewModel.state.value.pastDialogs as PastDialogsSectionState.Loaded
            assertEquals("h1", loaded.selectedConversationId)
            assertEquals(PastDialogHistoryState.Loaded(messages = listOf(message), nextBeforeSequence = null), loaded.history)
            assertEquals(listOf(Triple("c1", "h1", null)), hub.historyCalls)
        }

    @Test
    fun `closePastDialogHistory returns to the list`() =
        runTest(dispatcher) {
            val listApi =
                FakeVisitorHistoryApi(
                    results =
                        mutableListOf(
                            VisitorHistoryResult.Loaded(VisitorHistoryPage(listOf(historyConversation("h1")), nextBeforeId = null)),
                        ),
                )
            val hub = FakeOperatorHubEvents().apply { historyResults += HistoryPage() }
            val viewModel = viewModel(visitorHistoryApi = listApi, hubEvents = hub)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.openPastDialog("h1")
            advanceUntilIdle()
            viewModel.closePastDialogHistory()

            val loaded = viewModel.state.value.pastDialogs as PastDialogsSectionState.Loaded
            assertEquals(null, loaded.selectedConversationId)
            assertEquals(null, loaded.history)
        }

    @Test
    fun `a failed transcript fetch becomes Failed, and retryPastDialogHistory asks again`() =
        runTest(dispatcher) {
            val listApi =
                FakeVisitorHistoryApi(
                    results =
                        mutableListOf(
                            VisitorHistoryResult.Loaded(VisitorHistoryPage(listOf(historyConversation("h1")), nextBeforeId = null)),
                        ),
                )
            val hub = FakeOperatorHubEvents().apply { historyFailures += java.io.IOException("boom") }
            val viewModel = viewModel(visitorHistoryApi = listApi, hubEvents = hub)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.openPastDialog("h1")
            advanceUntilIdle()

            val loaded = viewModel.state.value.pastDialogs as PastDialogsSectionState.Loaded
            assertEquals(PastDialogHistoryState.Failed(NetworkFailure.NoConnection), loaded.history)

            hub.historyResults += HistoryPage(messages = listOf(MessageDto(id = "m1", sequence = 1)))
            viewModel.retryPastDialogHistory()
            advanceUntilIdle()

            val retried = viewModel.state.value.pastDialogs as PastDialogsSectionState.Loaded
            assertEquals(2, hub.historyCalls.size)
            assertTrue(retried.history is PastDialogHistoryState.Loaded)
        }

    @Test
    fun `loadOlderPastDialogHistory prepends an older page`() =
        runTest(dispatcher) {
            val listApi =
                FakeVisitorHistoryApi(
                    results =
                        mutableListOf(
                            VisitorHistoryResult.Loaded(VisitorHistoryPage(listOf(historyConversation("h1")), nextBeforeId = null)),
                        ),
                )
            val newer = MessageDto(id = "m2", sequence = 2)
            val older = MessageDto(id = "m1", sequence = 1)
            val hub =
                FakeOperatorHubEvents().apply {
                    historyResults += HistoryPage(messages = listOf(newer), nextBeforeSequence = 2L)
                    historyResults += HistoryPage(messages = listOf(older), nextBeforeSequence = null)
                }
            val viewModel = viewModel(visitorHistoryApi = listApi, hubEvents = hub)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.openPastDialog("h1")
            advanceUntilIdle()
            viewModel.loadOlderPastDialogHistory()
            advanceUntilIdle()

            val loaded = viewModel.state.value.pastDialogs as PastDialogsSectionState.Loaded
            assertEquals(
                PastDialogHistoryState.Loaded(messages = listOf(older, newer), nextBeforeSequence = null),
                loaded.history,
            )
            assertEquals(listOf<Long?>(null, 2L), hub.historyCalls.map { it.third })
        }

    private fun historyConversation(id: String): VisitorHistoryConversation =
        VisitorHistoryConversation(
            conversationId = id,
            state = "Closed",
            startedAt = null,
            closedAt = null,
            previewBody = null,
            previewAuthorKind = null,
            previewCreatedAt = null,
        )

    private class FakeVisitorHistoryApi(
        private val results: MutableList<VisitorHistoryResult> = mutableListOf(),
    ) : VisitorHistoryApi {
        var calls = 0
        val beforeIds: MutableList<String?> = mutableListOf()

        override suspend fun fetchVisitorHistory(
            conversationId: String,
            beforeId: String?,
            pageSize: Int,
        ): VisitorHistoryResult {
            calls++
            beforeIds.add(beforeId)
            return if (results.isNotEmpty()) results.removeAt(0) else VisitorHistoryResult.Loaded(VisitorHistoryPage(emptyList(), null))
        }
    }

    /** A minimal [OperatorHubEvents] fake — only [getVisitorHistoryConversation] is exercised by this view
     * model; every other member either returns an inert default (the `Flow`s) or fails loudly if this
     * class ever calls it, the identical "not used by this screen" posture `ThreadViewModelTest`'s own
     * `FakeOperatorHubEvents` establishes for the methods it does not exercise either. */
    private class FakeOperatorHubEvents : OperatorHubEvents {
        override val state: MutableStateFlow<OperatorHubConnectionState> = MutableStateFlow(OperatorHubConnectionState.Connected)
        override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)
        override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 16)
        override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)
        override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

        val historyResults: MutableList<HistoryPage> = mutableListOf()
        val historyFailures: MutableList<Exception> = mutableListOf()

        /** Every [getVisitorHistoryConversation] call, as `(conversationId, historicalConversationId,
         * beforeSequence)` - `26-151`'s own tests assert on this to prove the two-id arity and the
         * `beforeSequence` cursor are sent exactly as the port's own contract states. */
        val historyCalls: MutableList<Triple<String, String, Long?>> = mutableListOf()

        override suspend fun joinConversation(conversationId: String): HistoryPage = error("not used by this view model")

        override fun leaveConversation() = Unit

        override suspend fun loadOlderHistory(
            conversationId: String,
            beforeSequence: Long,
            pageSize: Int,
        ): HistoryPage = error("not used by this view model")

        override suspend fun getVisitorHistoryConversation(
            conversationId: String,
            historicalConversationId: String,
            beforeSequence: Long?,
            pageSize: Int,
        ): HistoryPage {
            historyCalls.add(Triple(conversationId, historicalConversationId, beforeSequence))
            if (historyFailures.isNotEmpty()) throw historyFailures.removeAt(0)
            return if (historyResults.isNotEmpty()) historyResults.removeAt(0) else HistoryPage()
        }

        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            clientMessageId: String,
            attachmentId: String?,
        ): SendMessageResult = error("not used by this view model")

        override suspend fun reconnectToActiveSite() = error("not used by this view model")

        override suspend fun getTeamHistory(
            beforeSequence: Long?,
            pageSize: Int,
        ): TeamHistoryPage = error("not used by this view model")

        override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage = error("not used by this view model")

        override suspend fun sendTeamMessage(
            body: String,
            clientMessageId: String,
        ): SendMessageResult = error("not used by this view model")

        override suspend fun removeTeamMessage(teamMessageId: String) = error("not used by this view model")
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
        private val editResult: ContactDetailWriteResult = ContactDetailWriteResult.Failed(NetworkFailure.Unexpected),
        private val assessmentResult: ContactDetailWriteResult = ContactDetailWriteResult.Failed(NetworkFailure.Unexpected),
    ) : ContactDetailsApi {
        var listCalls = 0
        var revealCalls = 0

        // `26-169`: the write half of this port now has a real caller - `ContactPanelViewModel
        // .saveEditContactDetail`/`.setContactDetailAssessment` - exercised by this file's own `26-169`
        // section below.
        var editCalls = 0
        var assessmentCalls = 0

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

        override suspend fun editContactDetail(
            conversationId: String,
            contactDetailId: String,
            value: String,
        ): ContactDetailWriteResult {
            editCalls++
            return editResult
        }

        override suspend fun setContactDetailAssessment(
            conversationId: String,
            contactDetailId: String,
            assessment: String,
        ): ContactDetailWriteResult {
            assessmentCalls++
            return assessmentResult
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

    // ─── 26-152: «Приём файлов от посетителя» toggle ──────────────────────────────────────────────────

    @Test
    fun `open loads the attachment-upload row into the Loaded arm`() =
        runTest(dispatcher) {
            val queue =
                ConversationQueue(
                    waiting = emptyList(),
                    assignedToMe =
                        listOf(
                            conversationSummary(
                                "c1",
                                hasAttachmentUploadGrant = true,
                                attachmentUploadGrantedAt = "2026-03-14T09:00:00Z",
                                attachmentUploadGrantedByOperatorId = "op1",
                            ),
                        ),
                )
            val viewModel = viewModel(conversationsApi = FakeConversationsApi(queueResult = QueueResult.Loaded(queue)))

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(
                AttachmentUploadSectionState.Loaded(
                    granted = true,
                    grantedAt = "2026-03-14T09:00:00Z",
                    grantedByOperatorId = "op1",
                ),
                viewModel.state.value.attachmentUpload,
            )
        }

    @Test
    fun `a failed queue read becomes the Failed arm, and its retry asks again`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(conversationsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(
                AttachmentUploadSectionState.Failed(NetworkFailure.NoConnection),
                viewModel.state.value.attachmentUpload,
            )

            viewModel.retryAttachmentUpload()
            advanceUntilIdle()

            assertEquals(2, api.queueCalls)
        }

    @Test
    fun `a row missing from the queue becomes Failed rather than a guessed not-granted`() =
        runTest(dispatcher) {
            val queue = ConversationQueue(waiting = emptyList(), assignedToMe = emptyList())
            val viewModel = viewModel(conversationsApi = FakeConversationsApi(queueResult = QueueResult.Loaded(queue)))

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(
                AttachmentUploadSectionState.Failed(NetworkFailure.Unexpected),
                viewModel.state.value.attachmentUpload,
            )
        }

    @Test
    fun `toggling a granted-false row grants it, re-reading the queue for the fresh who-when`() =
        runTest(dispatcher) {
            val notGranted = ConversationQueue(waiting = emptyList(), assignedToMe = listOf(conversationSummary("c1")))
            val granted =
                ConversationQueue(
                    waiting = emptyList(),
                    assignedToMe =
                        listOf(
                            conversationSummary(
                                "c1",
                                hasAttachmentUploadGrant = true,
                                attachmentUploadGrantedAt = "2026-03-14T09:00:00Z",
                                attachmentUploadGrantedByOperatorId = "op1",
                            ),
                        ),
                )
            val conversationsApi =
                FakeConversationsApi(
                    queueResults = mutableListOf(QueueResult.Loaded(notGranted), QueueResult.Loaded(granted)),
                )
            val actionsApi = FakeConversationActionsApi(grantResult = ConversationActionResult.Succeeded)
            val viewModel = viewModel(conversationsApi = conversationsApi, conversationActionsApi = actionsApi)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(false, (viewModel.state.value.attachmentUpload as AttachmentUploadSectionState.Loaded).granted)

            viewModel.toggleAttachmentUpload()
            advanceUntilIdle()

            assertEquals(1, actionsApi.grantCalls)
            assertEquals(0, actionsApi.revokeCalls)
            assertEquals(
                AttachmentUploadSectionState.Loaded(
                    granted = true,
                    grantedAt = "2026-03-14T09:00:00Z",
                    grantedByOperatorId = "op1",
                ),
                viewModel.state.value.attachmentUpload,
            )
        }

    @Test
    fun `toggling a granted-true row revokes it`() =
        runTest(dispatcher) {
            val queue =
                ConversationQueue(
                    waiting = emptyList(),
                    assignedToMe = listOf(conversationSummary("c1", hasAttachmentUploadGrant = true)),
                )
            val conversationsApi = FakeConversationsApi(queueResult = QueueResult.Loaded(queue))
            val actionsApi = FakeConversationActionsApi(revokeResult = ConversationActionResult.Succeeded)
            val viewModel = viewModel(conversationsApi = conversationsApi, conversationActionsApi = actionsApi)

            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.toggleAttachmentUpload()
            advanceUntilIdle()

            assertEquals(1, actionsApi.revokeCalls)
            assertEquals(0, actionsApi.grantCalls)
        }

    @Test
    fun `a refused toggle leaves granted untouched and surfaces the detail verbatim`() =
        runTest(dispatcher) {
            val queue = ConversationQueue(waiting = emptyList(), assignedToMe = listOf(conversationSummary("c1")))
            val conversationsApi = FakeConversationsApi(queueResult = QueueResult.Loaded(queue))
            val actionsApi =
                FakeConversationActionsApi(grantResult = ConversationActionResult.Refused("Уже отключено."))
            val viewModel = viewModel(conversationsApi = conversationsApi, conversationActionsApi = actionsApi)

            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.toggleAttachmentUpload()
            advanceUntilIdle()

            val loaded = viewModel.state.value.attachmentUpload as AttachmentUploadSectionState.Loaded
            assertEquals(false, loaded.granted)
            assertEquals(false, loaded.toggling)
            assertEquals(AttachmentUploadActionError.Refused("Уже отключено."), loaded.actionError)
            // A refused write asked the queue exactly once - the initial `open` read - never a second,
            // unneeded re-read the way a Succeeded result triggers.
            assertEquals(1, conversationsApi.queueCalls)
        }

    @Test
    fun `a failed toggle leaves granted untouched and surfaces a generic reason`() =
        runTest(dispatcher) {
            val queue = ConversationQueue(waiting = emptyList(), assignedToMe = listOf(conversationSummary("c1")))
            val conversationsApi = FakeConversationsApi(queueResult = QueueResult.Loaded(queue))
            val actionsApi = FakeConversationActionsApi(grantResult = ConversationActionResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(conversationsApi = conversationsApi, conversationActionsApi = actionsApi)

            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.toggleAttachmentUpload()
            advanceUntilIdle()

            val loaded = viewModel.state.value.attachmentUpload as AttachmentUploadSectionState.Loaded
            assertEquals(false, loaded.granted)
            assertEquals(AttachmentUploadActionError.Failed(NetworkFailure.NoConnection), loaded.actionError)
        }

    @Test
    fun `a toggle already in flight is a no-op`() =
        runTest(dispatcher) {
            val queue = ConversationQueue(waiting = emptyList(), assignedToMe = listOf(conversationSummary("c1")))
            val conversationsApi = FakeConversationsApi(queueResult = QueueResult.Loaded(queue))
            val actionsApi = FakeConversationActionsApi(grantHang = true)
            val viewModel = viewModel(conversationsApi = conversationsApi, conversationActionsApi = actionsApi)

            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.toggleAttachmentUpload()
            dispatcher.scheduler.runCurrent()
            viewModel.toggleAttachmentUpload()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, actionsApi.grantCalls)
        }

    private fun conversationSummary(
        conversationId: String,
        hasAttachmentUploadGrant: Boolean = false,
        attachmentUploadGrantedAt: String? = null,
        attachmentUploadGrantedByOperatorId: String? = null,
    ): ConversationSummary =
        ConversationSummary(
            conversationId = conversationId,
            visitorId = "v1",
            emojiCreature = null,
            emojiFood = null,
            visitorName = null,
            createdAt = "2026-03-14T08:00:00Z",
            operatorUnreadCount = 0,
            hasAttachmentUploadGrant = hasAttachmentUploadGrant,
            attachmentUploadGrantedAt = attachmentUploadGrantedAt,
            attachmentUploadGrantedByOperatorId = attachmentUploadGrantedByOperatorId,
        )

    private class FakeConversationsApi(
        queueResult: QueueResult = QueueResult.Loaded(ConversationQueue(emptyList(), emptyList())),
        private val queueResults: MutableList<QueueResult> = mutableListOf(queueResult),
    ) : ConversationsApi {
        var queueCalls = 0

        override suspend fun fetchQueue(): QueueResult {
            queueCalls++
            return if (queueResults.size > 1) queueResults.removeAt(0) else queueResults.first()
        }

        override suspend fun claim(conversationId: String): ClaimResult = error("not used by this view model")

        override suspend fun markRead(
            conversationId: String,
            upToSequence: Int,
        ): Boolean = error("not used by this view model")

        override suspend fun fetchAllConversations(
            beforeId: String?,
            pageSize: Int,
            states: List<String>,
        ): AllConversationsResult = error("not used by this view model")

        override suspend fun requestErasure(conversationId: String): ErasureResult = error("not used by this view model")
    }

    private class FakeConversationActionsApi(
        private val grantResult: ConversationActionResult = ConversationActionResult.Failed(NetworkFailure.Unexpected),
        private val revokeResult: ConversationActionResult = ConversationActionResult.Failed(NetworkFailure.Unexpected),
        private val closeResult: ConversationActionResult = ConversationActionResult.Failed(NetworkFailure.Unexpected),
        private val grantHang: Boolean = false,
        private val closeHang: Boolean = false,
    ) : ConversationActionsApi {
        var grantCalls = 0
        var revokeCalls = 0
        var closeCalls = 0

        override suspend fun close(conversationId: String): ConversationActionResult {
            closeCalls++
            if (closeHang) awaitCancellation()
            return closeResult
        }

        override suspend fun grantAttachmentUpload(conversationId: String): ConversationActionResult {
            grantCalls++
            if (grantHang) awaitCancellation()
            return grantResult
        }

        override suspend fun revokeAttachmentUpload(conversationId: String): ConversationActionResult {
            revokeCalls++
            return revokeResult
        }
    }

    // ─── 26-153: «Закрыть диалог» + reversible «Ограничить»/«Снять ограничение» ───────────────────────────

    @Test
    fun `closeConversation fires the one-shot event on success`() =
        runTest(dispatcher) {
            val api = FakeConversationActionsApi(closeResult = ConversationActionResult.Succeeded)
            val viewModel = viewModel(conversationActionsApi = api)
            val events = mutableListOf<Unit>()
            val collectJob = launch { viewModel.conversationClosed.collect { events.add(it) } }

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.closeConversation()
            advanceUntilIdle()

            assertEquals(1, api.closeCalls)
            assertEquals(1, events.size)
            // `closing` is deliberately left `true` on success - `closeConversation`'s own doc comment on
            // why there is no button left on screen to un-disable once the sheet is about to be dismissed.
            assertTrue(viewModel.state.value.closing)
            collectJob.cancel()
        }

    @Test
    fun `a refused close keeps the sheet up and surfaces the server detail`() =
        runTest(dispatcher) {
            val api = FakeConversationActionsApi(closeResult = ConversationActionResult.Refused("Не назначено вам."))
            val viewModel = viewModel(conversationActionsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.closeConversation()
            advanceUntilIdle()

            assertEquals(false, viewModel.state.value.closing)
            assertEquals(CloseActionError.Refused("Не назначено вам."), viewModel.state.value.closeError)
        }

    @Test
    fun `a failed close keeps the sheet up and surfaces a generic reason`() =
        runTest(dispatcher) {
            val api = FakeConversationActionsApi(closeResult = ConversationActionResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(conversationActionsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.closeConversation()
            advanceUntilIdle()

            assertEquals(CloseActionError.Failed(NetworkFailure.NoConnection), viewModel.state.value.closeError)
        }

    @Test
    fun `closeConversation already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeConversationActionsApi(closeHang = true)
            val viewModel = viewModel(conversationActionsApi = api)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.closeConversation()
            dispatcher.scheduler.runCurrent()
            viewModel.closeConversation()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.closeCalls)
        }

    @Test
    fun `open with no visitor id lands the restriction section Unavailable`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi()
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = null)
            advanceUntilIdle()

            assertEquals(RestrictionSectionState.Unavailable, viewModel.state.value.restriction)
            assertEquals(0, api.isRestrictedCalls)
        }

    @Test
    fun `open with a visitor id reads whether the visitor is restricted`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(statusResult = VisitorRestrictionStatusResult.Loaded(restricted = true))
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()

            assertEquals(RestrictionSectionState.Loaded(restricted = true), viewModel.state.value.restriction)
            assertEquals(listOf("v1"), api.isRestrictedIds)
        }

    @Test
    fun `a failed restriction read becomes the Failed arm, and retryRestriction asks again`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(statusResult = VisitorRestrictionStatusResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()
            assertEquals(RestrictionSectionState.Failed(NetworkFailure.NoConnection), viewModel.state.value.restriction)

            viewModel.retryRestriction()
            advanceUntilIdle()

            assertEquals(2, api.isRestrictedCalls)
        }

    @Test
    fun `reopening with a resolved visitor id re-reads restriction even for the same conversation`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi()
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = null)
            advanceUntilIdle()
            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()

            assertEquals(RestrictionSectionState.Loaded(restricted = false), viewModel.state.value.restriction)
            assertEquals(1, api.isRestrictedCalls)
        }

    @Test
    fun `toggleRestriction blocks an unrestricted visitor and flips the flag on success`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    statusResult = VisitorRestrictionStatusResult.Loaded(restricted = false),
                    blockResult = VisitorRestrictionActionResult.Succeeded,
                )
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()
            viewModel.toggleRestriction()
            advanceUntilIdle()

            assertEquals(1, api.blockCalls)
            assertEquals(0, api.liftCalls)
            assertEquals(
                RestrictionSectionState.Loaded(restricted = true),
                viewModel.state.value.restriction,
            )
            // No second read of the (expensive, paged) status endpoint - the flip is derived from the
            // write's own known outcome, not a re-read.
            assertEquals(1, api.isRestrictedCalls)
        }

    @Test
    fun `toggleRestriction lifts a restricted visitor by their visitor id`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    statusResult = VisitorRestrictionStatusResult.Loaded(restricted = true),
                    liftResult = VisitorRestrictionActionResult.Succeeded,
                )
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()
            viewModel.toggleRestriction()
            advanceUntilIdle()

            assertEquals(1, api.liftCalls)
            assertEquals(0, api.blockCalls)
            assertEquals(listOf("v1"), api.liftIds)
            assertEquals(RestrictionSectionState.Loaded(restricted = false), viewModel.state.value.restriction)
        }

    @Test
    fun `a refused toggle leaves restricted untouched and surfaces the detail verbatim`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    statusResult = VisitorRestrictionStatusResult.Loaded(restricted = false),
                    blockResult = VisitorRestrictionActionResult.Refused("Не хватает прав."),
                )
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()
            viewModel.toggleRestriction()
            advanceUntilIdle()

            val loaded = viewModel.state.value.restriction as RestrictionSectionState.Loaded
            assertEquals(false, loaded.restricted)
            assertEquals(false, loaded.toggling)
            assertEquals(RestrictionActionError.Refused("Не хватает прав."), loaded.actionError)
        }

    @Test
    fun `a restriction toggle already in flight is a no-op`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    statusResult = VisitorRestrictionStatusResult.Loaded(restricted = false),
                    blockHang = true,
                )
            val viewModel = viewModel(visitorRestrictionApi = api)

            viewModel.open("c1", visitorId = "v1")
            advanceUntilIdle()
            viewModel.toggleRestriction()
            dispatcher.scheduler.runCurrent()
            viewModel.toggleRestriction()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.blockCalls)
        }

    private class FakeVisitorRestrictionApi(
        private val statusResult: VisitorRestrictionStatusResult = VisitorRestrictionStatusResult.Loaded(restricted = false),
        private val blockResult: VisitorRestrictionActionResult = VisitorRestrictionActionResult.Failed(NetworkFailure.Unexpected),
        private val liftResult: VisitorRestrictionActionResult = VisitorRestrictionActionResult.Failed(NetworkFailure.Unexpected),
        private val blockHang: Boolean = false,
    ) : VisitorRestrictionApi {
        var isRestrictedCalls = 0
        var blockCalls = 0
        var liftCalls = 0
        val isRestrictedIds: MutableList<String> = mutableListOf()
        val liftIds: MutableList<String> = mutableListOf()

        override suspend fun block(conversationId: String): VisitorRestrictionActionResult {
            blockCalls++
            if (blockHang) awaitCancellation()
            return blockResult
        }

        override suspend fun lift(visitorId: String): VisitorRestrictionActionResult {
            liftCalls++
            liftIds.add(visitorId)
            return liftResult
        }

        override suspend fun isRestricted(visitorId: String): VisitorRestrictionStatusResult {
            isRestrictedCalls++
            isRestrictedIds.add(visitorId)
            return statusResult
        }
    }
}
