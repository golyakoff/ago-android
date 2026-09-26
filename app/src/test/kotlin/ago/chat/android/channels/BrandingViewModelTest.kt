package ago.chat.android.channels

import ago.chat.android.core.domain.branding.BrandingWriteResult
import ago.chat.android.core.domain.branding.LogoStatus
import ago.chat.android.core.domain.branding.LogoUploadResult
import ago.chat.android.core.domain.branding.SiteBranding
import ago.chat.android.core.domain.branding.SiteBrandingApi
import ago.chat.android.core.domain.branding.SiteBrandingResult
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

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.3): [BrandingViewModel]'s own read and
 * two-independent-writes surface — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * every sibling view-model test in this app already establishes
 * ([ago.chat.android.channels.WidgetConfigViewModelTest]).
 *
 * The load-bearing tests here are the two "independent" claims
 * (`docs/design/tenant-channels-android.md` §3.3's own "independent state for name-save vs
 * logo-upload"): a name save in flight never blocks a concurrent logo upload, and each write's own
 * error/tick never leaks onto the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrandingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val api = FakeSiteBrandingApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(BrandingUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the branding arrives and is seeded as loaded`() =
        runTest(dispatcher) {
            val api = FakeSiteBrandingApi(fetchResult = SiteBrandingResult.Loaded(fullBranding()))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(
                BrandingUiState.Loaded(
                    brandCompanyName = "Cool Shop",
                    logoUrl = "https://cdn.example/logo.png",
                    logoStatus = LogoStatus.Ready,
                    logoRejectionReason = null,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeSiteBrandingApi(fetchResult = SiteBrandingResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(BrandingUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeSiteBrandingApi(fetchResult = SiteBrandingResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is BrandingUiState.Failed)

            api.fetchResult = SiteBrandingResult.Loaded(fullBranding())
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is BrandingUiState.Loaded)
        }

    // --------------------------------------------------------- saveCompanyName

    @Test
    fun `a successful name save re-seeds the committed name from the server's own echo and bumps nameSavedTick`() =
        runTest(dispatcher) {
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(fullBranding()),
                    updateResult = BrandingWriteResult.Saved("Normalised Name"),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.saveCompanyName("New Name")
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertEquals("Normalised Name", state.brandCompanyName)
            assertEquals(1, state.nameSavedTick)
            assertFalse(state.savingName)
            assertNull(state.nameError)
        }

    @Test
    fun `a refused name save keeps the committed name unchanged and shows the server's own words`() =
        runTest(dispatcher) {
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(fullBranding()),
                    updateResult = BrandingWriteResult.Refused("Название компании слишком длинное."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.saveCompanyName("x".repeat(500))
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertEquals("a refusal never changes the committed name", "Cool Shop", state.brandCompanyName)
            assertFalse(state.savingName)
            assertEquals(BrandingActionError.ServerRefusal("Название компании слишком длинное."), state.nameError)
        }

    @Test
    fun `a transport failure on name save is Unavailable, committed name unchanged`() =
        runTest(dispatcher) {
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(fullBranding()),
                    updateResult = BrandingWriteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.saveCompanyName("New Name")
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertEquals("Cool Shop", state.brandCompanyName)
            assertEquals(BrandingActionError.Unavailable(NetworkFailure.NoConnection), state.nameError)
        }

    // --------------------------------------------------------- uploadLogo

    @Test
    fun `an accepted upload updates logoStatus and clears any earlier rejection reason`() =
        runTest(dispatcher) {
            val rejected = fullBranding().copy(logoStatus = LogoStatus.Rejected, logoRejectionReason = "Анимированный GIF.")
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(rejected),
                    uploadResult = LogoUploadResult.Accepted(LogoStatus.Pending),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.uploadLogo(byteArrayOf(1, 2, 3), "image/png")
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertEquals(LogoStatus.Pending, state.logoStatus)
            assertNull(state.logoRejectionReason)
            assertFalse(state.uploading)
            assertNull(state.uploadError)
            assertEquals(listOf("image/png"), api.uploadedContentTypes)
        }

    @Test
    fun `a refused upload keeps logoStatus unchanged and shows the server's own words`() =
        runTest(dispatcher) {
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(fullBranding()),
                    uploadResult = LogoUploadResult.Refused("Слишком много загрузок логотипа сегодня."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.uploadLogo(byteArrayOf(1), "image/png")
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertEquals("a refusal never changes logoStatus", LogoStatus.Ready, state.logoStatus)
            assertFalse(state.uploading)
            assertEquals(BrandingActionError.ServerRefusal("Слишком много загрузок логотипа сегодня."), state.uploadError)
        }

    @Test
    fun `a transport failure on upload is Unavailable`() =
        runTest(dispatcher) {
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(fullBranding()),
                    uploadResult = LogoUploadResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.uploadLogo(byteArrayOf(1), "image/png")
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertEquals(BrandingActionError.Unavailable(NetworkFailure.NoConnection), state.uploadError)
        }

    // --------------------------------------------------------- independence (§3.3's own hard requirement)

    @Test
    fun `a name save in flight never blocks a concurrent logo upload`() =
        runTest(dispatcher) {
            val api =
                FakeSiteBrandingApi(
                    fetchResult = SiteBrandingResult.Loaded(fullBranding()),
                    hangUpdate = true,
                    uploadResult = LogoUploadResult.Accepted(LogoStatus.Pending),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.saveCompanyName("New Name")
            dispatcher.scheduler.runCurrent()
            assertTrue((viewModel.state.value as BrandingUiState.Loaded).savingName)

            viewModel.uploadLogo(byteArrayOf(1), "image/png")
            advanceUntilIdle()

            val state = viewModel.state.value as BrandingUiState.Loaded
            assertTrue("the still-in-flight name save must not be disturbed by the upload", state.savingName)
            assertEquals(LogoStatus.Pending, state.logoStatus)
            assertFalse(state.uploading)
        }

    @Test
    fun `a second name save while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeSiteBrandingApi(fetchResult = SiteBrandingResult.Loaded(fullBranding()), hangUpdate = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.saveCompanyName("First")
            dispatcher.scheduler.runCurrent()
            viewModel.saveCompanyName("Second")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("First"), api.updatedNames)
        }

    @Test
    fun `a second logo upload while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeSiteBrandingApi(fetchResult = SiteBrandingResult.Loaded(fullBranding()), hangUpload = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.uploadLogo(byteArrayOf(1), "image/png")
            dispatcher.scheduler.runCurrent()
            viewModel.uploadLogo(byteArrayOf(2), "image/gif")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("image/png"), api.uploadedContentTypes)
        }

    private fun viewModel(api: FakeSiteBrandingApi) = BrandingViewModel(api = api, ioDispatcher = dispatcher)

    private fun fullBranding() =
        SiteBranding(
            brandCompanyName = "Cool Shop",
            logoUrl = "https://cdn.example/logo.png",
            logoStatus = LogoStatus.Ready,
            logoRejectionReason = null,
        )

    private class FakeSiteBrandingApi(
        var fetchResult: SiteBrandingResult = SiteBrandingResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
        var updateResult: BrandingWriteResult = BrandingWriteResult.Failed(NetworkFailure.Unexpected),
        private val hangUpdate: Boolean = false,
        var uploadResult: LogoUploadResult = LogoUploadResult.Failed(NetworkFailure.Unexpected),
        private val hangUpload: Boolean = false,
    ) : SiteBrandingApi {
        val updatedNames: MutableList<String?> = mutableListOf()
        val uploadedContentTypes: MutableList<String> = mutableListOf()

        override suspend fun fetch(): SiteBrandingResult {
            if (hangFetch) awaitCancellation()
            return fetchResult
        }

        override suspend fun updateCompanyName(name: String?): BrandingWriteResult {
            updatedNames.add(name)
            if (hangUpdate) awaitCancellation()
            return updateResult
        }

        override suspend fun uploadLogo(
            bytes: ByteArray,
            contentType: String,
        ): LogoUploadResult {
            uploadedContentTypes.add(contentType)
            if (hangUpload) awaitCancellation()
            return uploadResult
        }
    }
}
