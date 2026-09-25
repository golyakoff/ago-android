package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.net.NetworkFailure
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
    ) = ContactPanelViewModel(
        visitorSummaryApi = summaryApi,
        contactDetailsApi = contactDetailsApi,
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
}
