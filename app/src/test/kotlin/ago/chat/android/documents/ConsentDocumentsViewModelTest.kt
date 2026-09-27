package ago.chat.android.documents

import ago.chat.android.core.domain.consent.ConsentAcceptance
import ago.chat.android.core.domain.consent.ConsentAcceptancesResult
import ago.chat.android.core.domain.consent.ConsentDocumentBounds
import ago.chat.android.core.domain.consent.ConsentDocumentSummary
import ago.chat.android.core.domain.consent.ConsentOverview
import ago.chat.android.core.domain.consent.ConsentPublishResult
import ago.chat.android.core.domain.consent.ConsentPurpose
import ago.chat.android.core.domain.consent.ConsentVersion
import ago.chat.android.core.domain.consent.SiteConsentDocumentsApi
import ago.chat.android.core.domain.consent.SiteConsentDocumentsResult
import ago.chat.android.core.domain.net.NetworkFailure
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §3): [ConsentDocumentsViewModel]'s own
 * read/acceptances/publish surface - the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * every sibling view-model test in this app already establishes
 * ([ago.chat.android.automation.CannedResponsesViewModelTest]).
 *
 * The load-bearing tests here are: [ConsentDocumentsViewModel.loadAcceptances] never repeats a network
 * call for an already-loaded key, and two different versions never merge their lists; [publish] runs
 * the courtesy check before ever touching the network, never trusts its own echo, and reloads a fresh
 * overview on success; and `409` becomes [ConsentPublishActionError.Conflict], never a generic refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentDocumentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --------------------------------------------------------------------------------------------- refresh

    @Test
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val api = FakeSiteConsentDocumentsApi(hangFetchOverview = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(ConsentDocumentsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the overview arrives and is seeded as loaded`() =
        runTest(dispatcher) {
            val overview = sampleOverview()
            val api = FakeSiteConsentDocumentsApi(fetchOverviewResult = SiteConsentDocumentsResult.Loaded(overview))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(overview, state.overview)
            assertTrue(state.acceptances.isEmpty())
            assertFalse(state.publishing)
            assertNull(state.publishError)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeSiteConsentDocumentsApi(fetchOverviewResult = SiteConsentDocumentsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(ConsentDocumentsUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeSiteConsentDocumentsApi(fetchOverviewResult = SiteConsentDocumentsResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is ConsentDocumentsUiState.Failed)

            api.fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview())
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is ConsentDocumentsUiState.Loaded)
        }

    // -------------------------------------------------------------------------------------- loadAcceptances

    @Test
    fun `loadAcceptances filters the whole-kind read down to the requested version`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    fetchAcceptancesResult =
                        ConsentAcceptancesResult.Loaded(
                            listOf(
                                acceptance("v1"),
                                acceptance("v2"),
                            ),
                        ),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val key = AcceptanceKey(ConsentPurpose.Contact, "v2")
            viewModel.loadAcceptances(key)
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            val entry = state.acceptances.getValue(key) as AcceptancesUiState.Loaded
            assertEquals(listOf(acceptance("v2")), entry.acceptances)
        }

    @Test
    fun `a second load of an already-loaded key never repeats the network call`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    fetchAcceptancesResult = ConsentAcceptancesResult.Loaded(listOf(acceptance("v1"))),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val key = AcceptanceKey(ConsentPurpose.Contact, "v1")
            viewModel.loadAcceptances(key)
            advanceUntilIdle()
            viewModel.loadAcceptances(key)
            advanceUntilIdle()

            assertEquals("a second expand of the same version must never repeat the network call", 1, api.fetchAcceptancesCalls)
        }

    @Test
    fun `two different versions get two independent lists, never merged`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    fetchAcceptancesResult = ConsentAcceptancesResult.Loaded(listOf(acceptance("v1"), acceptance("v2"))),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val keyV1 = AcceptanceKey(ConsentPurpose.Contact, "v1")
            val keyV2 = AcceptanceKey(ConsentPurpose.Contact, "v2")
            viewModel.loadAcceptances(keyV1)
            advanceUntilIdle()
            viewModel.loadAcceptances(keyV2)
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(listOf(acceptance("v1")), (state.acceptances.getValue(keyV1) as AcceptancesUiState.Loaded).acceptances)
            assertEquals(listOf(acceptance("v2")), (state.acceptances.getValue(keyV2) as AcceptancesUiState.Loaded).acceptances)
        }

    @Test
    fun `a failed acceptances read is Failed for that key alone`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    fetchAcceptancesResult = ConsentAcceptancesResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val key = AcceptanceKey(ConsentPurpose.Contact, "v1")
            viewModel.loadAcceptances(key)
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(AcceptancesUiState.Failed(NetworkFailure.NoConnection), state.acceptances.getValue(key))
        }

    @Test
    fun `retryAcceptances re-fetches after a failure`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    fetchAcceptancesResult = ConsentAcceptancesResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val key = AcceptanceKey(ConsentPurpose.Contact, "v1")
            viewModel.loadAcceptances(key)
            advanceUntilIdle()

            api.fetchAcceptancesResult = ConsentAcceptancesResult.Loaded(listOf(acceptance("v1")))
            viewModel.retryAcceptances(key)
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(listOf(acceptance("v1")), (state.acceptances.getValue(key) as AcceptancesUiState.Loaded).acceptances)
            assertEquals(2, api.fetchAcceptancesCalls)
        }

    // ---------------------------------------------------------------------------------------------- publish

    @Test
    fun `publish runs the courtesy check first - a blank title never calls the network`() =
        runTest(dispatcher) {
            val api = FakeSiteConsentDocumentsApi(fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "  ", "текст")
            advanceUntilIdle()

            assertEquals("an invalid draft must never reach the network", 0, api.publishCalls)
            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(
                ConsentPublishActionError.Invalid(ConsentPublishValidationProblem.TitleRequired),
                state.publishError,
            )
            assertFalse(state.publishing)
        }

    @Test
    fun `publish rejects a title over the bound without calling the network`() =
        runTest(dispatcher) {
            val api = FakeSiteConsentDocumentsApi(fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val tooLong = "т".repeat(ConsentDocumentBounds.MAX_TITLE_LENGTH + 1)
            viewModel.publish(ConsentPurpose.Contact, tooLong, "текст")
            advanceUntilIdle()

            assertEquals(0, api.publishCalls)
            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(ConsentPublishActionError.Invalid(ConsentPublishValidationProblem.TitleTooLong), state.publishError)
        }

    @Test
    fun `publish trims the title but sends the body as typed`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    publishResult = ConsentPublishResult.Published(ConsentVersion("v3", 3, "t", Instant.EPOCH)),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "  Заголовок  ", "  Текст с пробелами  ")
            advanceUntilIdle()

            assertEquals("Заголовок", api.lastPublishTitle)
            assertEquals("  Текст с пробелами  ", api.lastPublishBody)
        }

    @Test
    fun `a successful publish reloads a fresh overview rather than trusting its own echo`() =
        runTest(dispatcher) {
            val reloaded = sampleOverview().copy(contactConsentRequired = false)
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    publishResult = ConsentPublishResult.Published(ConsentVersion("v3", 3, "t", Instant.EPOCH)),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            api.fetchOverviewResult = SiteConsentDocumentsResult.Loaded(reloaded)
            viewModel.publish(ConsentPurpose.Contact, "Заголовок", "Текст")
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(reloaded, state.overview)
            assertEquals(1, state.savedTick)
            assertFalse(state.publishing)
            assertNull(state.publishError)
            assertEquals(2, api.fetchOverviewCalls)
        }

    @Test
    fun `a second successful publish bumps savedTick again`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    publishResult = ConsentPublishResult.Published(ConsentVersion("v3", 3, "t", Instant.EPOCH)),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "a", "b")
            advanceUntilIdle()
            viewModel.publish(ConsentPurpose.Marketing, "c", "d")
            advanceUntilIdle()

            assertEquals(2, (viewModel.state.value as ConsentDocumentsUiState.Loaded).savedTick)
        }

    @Test
    fun `a 409 becomes Conflict, not a generic refusal`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    publishResult = ConsentPublishResult.Conflict,
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "a", "b")
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(ConsentPublishActionError.Conflict, state.publishError)
            assertFalse(state.publishing)
        }

    @Test
    fun `a refused publish shows the server's own words`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    publishResult = ConsentPublishResult.Refused("Заголовок не может быть пустым."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "a", "b")
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(ConsentPublishActionError.ServerRefusal("Заголовок не может быть пустым."), state.publishError)
        }

    @Test
    fun `a transport failure on publish is Unavailable`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(
                    fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()),
                    publishResult = ConsentPublishResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "a", "b")
            advanceUntilIdle()

            val state = viewModel.state.value as ConsentDocumentsUiState.Loaded
            assertEquals(ConsentPublishActionError.Unavailable(NetworkFailure.NoConnection), state.publishError)
            assertFalse(state.publishing)
        }

    @Test
    fun `a second publish while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api =
                FakeSiteConsentDocumentsApi(fetchOverviewResult = SiteConsentDocumentsResult.Loaded(sampleOverview()), hangPublish = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.publish(ConsentPurpose.Contact, "a", "b")
            dispatcher.scheduler.runCurrent()
            assertTrue((viewModel.state.value as ConsentDocumentsUiState.Loaded).publishing)

            viewModel.publish(ConsentPurpose.Marketing, "c", "d")
            dispatcher.scheduler.runCurrent()

            assertEquals("a write already in flight must never let a second one reach the network", 1, api.publishCalls)
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun viewModel(api: SiteConsentDocumentsApi) = ConsentDocumentsViewModel(api = api, ioDispatcher = dispatcher)

    private fun sampleOverview() =
        ConsentOverview(
            contact =
                ConsentDocumentSummary(
                    purpose = ConsentPurpose.Contact,
                    documentKey = "contact-key",
                    versions =
                        listOf(
                            ConsentVersion("v2", 2, "Согласие", Instant.parse("2026-01-02T00:00:00Z")),
                            ConsentVersion("v1", 1, "Согласие (старое)", Instant.parse("2026-01-01T00:00:00Z")),
                        ),
                ),
            contactConsentRequired = true,
            marketing = ConsentDocumentSummary(purpose = ConsentPurpose.Marketing, documentKey = "marketing-key", versions = emptyList()),
        )

    private fun acceptance(version: String) = ConsentAcceptance("Visitor", "a0f3c952-0000-0000-0000-000000000000", version, Instant.EPOCH)

    private class FakeSiteConsentDocumentsApi(
        var fetchOverviewResult: SiteConsentDocumentsResult = SiteConsentDocumentsResult.Failed(NetworkFailure.Unexpected),
        private val hangFetchOverview: Boolean = false,
        var fetchAcceptancesResult: ConsentAcceptancesResult = ConsentAcceptancesResult.Loaded(emptyList()),
        var publishResult: ConsentPublishResult = ConsentPublishResult.Failed(NetworkFailure.Unexpected),
        private val hangPublish: Boolean = false,
    ) : SiteConsentDocumentsApi {
        var fetchOverviewCalls: Int = 0
            private set
        var fetchAcceptancesCalls: Int = 0
            private set
        var publishCalls: Int = 0
            private set
        var lastPublishTitle: String? = null
            private set
        var lastPublishBody: String? = null
            private set

        override suspend fun fetchOverview(): SiteConsentDocumentsResult {
            fetchOverviewCalls++
            if (hangFetchOverview) awaitCancellation()
            return fetchOverviewResult
        }

        override suspend fun fetchAcceptances(purpose: ConsentPurpose): ConsentAcceptancesResult {
            fetchAcceptancesCalls++
            return fetchAcceptancesResult
        }

        override suspend fun publish(
            purpose: ConsentPurpose,
            title: String,
            body: String,
        ): ConsentPublishResult {
            publishCalls++
            lastPublishTitle = title
            lastPublishBody = body
            if (hangPublish) awaitCancellation()
            return publishResult
        }
    }
}
