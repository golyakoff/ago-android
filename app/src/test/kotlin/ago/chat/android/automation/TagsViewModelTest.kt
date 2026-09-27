package ago.chat.android.automation

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.SiteTagsApi
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagBounds
import ago.chat.android.core.domain.tags.TagDeleteResult
import ago.chat.android.core.domain.tags.TagMutationResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
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

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2.4): [TagsViewModel]'s own read-then-mutate
 * surface — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling view-model
 * test in this app already establishes ([ago.chat.android.automation.CannedResponsesViewModelTest]).
 *
 * The load-bearing tests here are: [TagsViewModel.create]/[TagsViewModel.rename]/[TagsViewModel.delete]
 * each make exactly one call to [SiteTagsApi], never a whole-list write; a success always re-fetches the
 * vocabulary rather than splicing the mutation's own echo in; only `create`/`rename` bump `savedTick`
 * (`delete`'s own confirm already dismissed itself before the call); and a courtesy-check failure never
 * reaches the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --------------------------------------------------------------------------------------------- fetch

    @Test
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(TagsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the vocabulary arrives and is seeded as loaded`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата"))))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(listOf(tag("Оплата")), state.tags)
            assertFalse(state.busy)
            assertNull(state.error)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(TagsUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is TagsUiState.Failed)

            api.fetchResult = TagVocabularyResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is TagsUiState.Loaded)
        }

    // ---------------------------------------------------------------------------------------------- create

    @Test
    fun `create runs the courtesy check first - a blank name never calls the network`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Loaded(emptyList()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.create("   ")
            advanceUntilIdle()

            assertEquals("an invalid name must never reach the network", 0, api.createCalls)
            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(TagsActionError.Invalid(TagValidationProblem.NameRequired), state.error)
            assertFalse(state.busy)
        }

    @Test
    fun `create runs the courtesy check first - an over-length name never calls the network`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Loaded(emptyList()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.create("а".repeat(TagBounds.MAX_NAME_LENGTH + 1))
            advanceUntilIdle()

            assertEquals(0, api.createCalls)
            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(TagsActionError.Invalid(TagValidationProblem.NameTooLong), state.error)
        }

    @Test
    fun `create trims the name before sending`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(emptyList()),
                    createResult = TagMutationResult.Saved(tag("Срочно")),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.create("  Срочно  ")
            advanceUntilIdle()

            assertEquals("Срочно", api.lastCreateName)
        }

    @Test
    fun `a successful create re-fetches the vocabulary and bumps savedTick`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(emptyList()),
                    createResult = TagMutationResult.Saved(tag("Срочно")),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            api.fetchResult = TagVocabularyResult.Loaded(listOf(tag("Срочно")))
            viewModel.create("Срочно")
            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(listOf(tag("Срочно")), state.tags)
            assertEquals(1, state.savedTick)
            assertFalse(state.busy)
            assertNull(state.error)
            assertEquals("re-fetches rather than splicing the echo in", 2, api.fetchCalls)
        }

    @Test
    fun `a duplicate-name refusal keeps the vocabulary and shows the server's own words, never bumping savedTick`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(listOf(tag("Срочно"))),
                    createResult = TagMutationResult.Refused("Метка с таким названием уже существует."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.create("Срочно")
            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(listOf(tag("Срочно")), state.tags)
            assertEquals(0, state.savedTick)
            assertFalse(state.busy)
            assertEquals(TagsActionError.ServerRefusal("Метка с таким названием уже существует."), state.error)
        }

    @Test
    fun `a transport failure on create is Unavailable`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(emptyList()),
                    createResult = TagMutationResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.create("Срочно")
            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(TagsActionError.Unavailable(NetworkFailure.NoConnection), state.error)
            assertFalse(state.busy)
        }

    // ---------------------------------------------------------------------------------------------- rename

    @Test
    fun `rename sends the tag id and the trimmed name`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата", id = "t1"))),
                    renameResult = TagMutationResult.Saved(tag("Оплата картой", id = "t1")),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.rename("t1", "  Оплата картой  ")
            advanceUntilIdle()

            assertEquals("t1" to "Оплата картой", api.lastRenameRequest)
        }

    @Test
    fun `a successful rename re-fetches the vocabulary and bumps savedTick`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата", id = "t1"))),
                    renameResult = TagMutationResult.Saved(tag("Оплата картой", id = "t1")),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            api.fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата картой", id = "t1")))
            viewModel.rename("t1", "Оплата картой")
            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(listOf(tag("Оплата картой", id = "t1")), state.tags)
            assertEquals(1, state.savedTick)
        }

    @Test
    fun `a rename courtesy-check failure never calls the network`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата", id = "t1"))))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.rename("t1", "")
            advanceUntilIdle()

            assertEquals(0, api.renameCalls)
            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(TagsActionError.Invalid(TagValidationProblem.NameRequired), state.error)
        }

    // ---------------------------------------------------------------------------------------------- delete

    @Test
    fun `a successful delete re-fetches the vocabulary but never bumps savedTick`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата", id = "t1"), tag("Срочно", id = "t2"))),
                    deleteResult = TagDeleteResult.Deleted,
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            api.fetchResult = TagVocabularyResult.Loaded(listOf(tag("Срочно", id = "t2")))
            viewModel.delete("t1")
            advanceUntilIdle()

            assertEquals("t1", api.lastDeleteId)
            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(listOf(tag("Срочно", id = "t2")), state.tags)
            assertEquals("delete never bumps savedTick - the confirm dialog already dismissed itself", 0, state.savedTick)
            assertFalse(state.busy)
        }

    @Test
    fun `a delete of a tag another operator already removed is rendered verbatim`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата", id = "t1"))),
                    deleteResult = TagDeleteResult.Refused("Метка не найдена."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.delete("t1")
            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(listOf(tag("Оплата", id = "t1")), state.tags)
            assertEquals(TagsActionError.ServerRefusal("Метка не найдена."), state.error)
            assertFalse(state.busy)
        }

    @Test
    fun `a transport failure on delete is Unavailable`() =
        runTest(dispatcher) {
            val api =
                FakeSiteTagsApi(
                    fetchResult = TagVocabularyResult.Loaded(listOf(tag("Оплата", id = "t1"))),
                    deleteResult = TagDeleteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.delete("t1")
            advanceUntilIdle()

            val state = viewModel.state.value as TagsUiState.Loaded
            assertEquals(TagsActionError.Unavailable(NetworkFailure.NoConnection), state.error)
        }

    // --------------------------------------------------------------------------------------------- re-entrancy

    @Test
    fun `a second mutation while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeSiteTagsApi(fetchResult = TagVocabularyResult.Loaded(emptyList()), hangCreate = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.create("Первая")
            dispatcher.scheduler.runCurrent()
            assertTrue((viewModel.state.value as TagsUiState.Loaded).busy)

            viewModel.create("Вторая")
            viewModel.delete("t1")
            dispatcher.scheduler.runCurrent()

            assertEquals("a mutation already in flight must never let a second one reach the network", 1, api.createCalls)
            assertEquals(0, api.deleteCalls)
        }

    private fun tag(
        name: String,
        id: String = name,
    ) = Tag(id = id, name = name, createdAt = "2026-09-01T00:00:00Z")

    private fun viewModel(api: FakeSiteTagsApi) = TagsViewModel(api = api, ioDispatcher = dispatcher)

    private class FakeSiteTagsApi(
        var fetchResult: TagVocabularyResult = TagVocabularyResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
        var createResult: TagMutationResult = TagMutationResult.Failed(NetworkFailure.Unexpected),
        private val hangCreate: Boolean = false,
        var renameResult: TagMutationResult = TagMutationResult.Failed(NetworkFailure.Unexpected),
        var deleteResult: TagDeleteResult = TagDeleteResult.Failed(NetworkFailure.Unexpected),
    ) : SiteTagsApi {
        var fetchCalls: Int = 0
            private set
        var createCalls: Int = 0
            private set
        var lastCreateName: String? = null
            private set
        var renameCalls: Int = 0
            private set
        var lastRenameRequest: Pair<String, String>? = null
            private set
        var deleteCalls: Int = 0
            private set
        var lastDeleteId: String? = null
            private set

        override suspend fun fetch(): TagVocabularyResult {
            fetchCalls++
            if (hangFetch) awaitCancellation()
            return fetchResult
        }

        override suspend fun create(name: String): TagMutationResult {
            createCalls++
            lastCreateName = name
            if (hangCreate) awaitCancellation()
            return createResult
        }

        override suspend fun rename(
            tagId: String,
            name: String,
        ): TagMutationResult {
            renameCalls++
            lastRenameRequest = tagId to name
            return renameResult
        }

        override suspend fun delete(tagId: String): TagDeleteResult {
            deleteCalls++
            lastDeleteId = tagId
            return deleteResult
        }
    }
}
