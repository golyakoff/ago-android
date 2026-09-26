package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.recut.RecutApi
import ago.chat.android.core.domain.recut.RecutBooking
import ago.chat.android.core.domain.recut.RecutBookingDecision
import ago.chat.android.core.domain.recut.RecutBookingStatus
import ago.chat.android.core.domain.recut.RecutConfirmResult
import ago.chat.android.core.domain.recut.RecutConfirmation
import ago.chat.android.core.domain.recut.RecutDay
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.recut.RecutPreview
import ago.chat.android.core.domain.recut.RecutPreviewResult
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
 * `26-172` (`26-155` part 4, the epic's own final part): the «Пересчёт» drill-down's own view model -
 * the full three steps `26-170`'s own read-only preview hook stopped short of: per-booking decisions, an
 * `AlertDialog` confirm, a result, and `recut.stale`/`recut.day_changed_concurrently` handled specially.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerRecutViewModelTest {
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
    fun `opening with no initial from defaults to today, previewing nothing yet`() =
        runTest(dispatcher) {
            val viewModel = WorkerRecutViewModel(FakeRecutApi(), FakeRecutBookingsApi(), dispatcher)

            viewModel.open("w1", initialFrom = null)

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertTrue(loaded.from.isNotBlank())
            assertNull(loaded.preview)
            assertFalse(loaded.previewing)
        }

    @Test
    fun `opening with an initial from - reached from the Часы notice - prefills it verbatim`() =
        runTest(dispatcher) {
            val viewModel = WorkerRecutViewModel(FakeRecutApi(), FakeRecutBookingsApi(), dispatcher)

            viewModel.open("w1", initialFrom = "2026-10-01")

            assertEquals("2026-10-01", (viewModel.state.value as WorkerRecutUiState.Loaded).from)
        }

    @Test
    fun `re-opening the same worker id still starts fresh - unlike the sibling drill-downs`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Loaded(PREVIEW))
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-01")
            viewModel.preview()
            advanceUntilIdle()
            assertTrue((viewModel.state.value as WorkerRecutUiState.Loaded).preview != null)

            viewModel.open("w1", initialFrom = "2026-10-15")

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals("2026-10-15", loaded.from)
            assertNull(loaded.preview)
        }

    @Test
    fun `previewing sends the workerId and the typed from, and clears any earlier result`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Loaded(PREVIEW))
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")

            viewModel.preview()
            advanceUntilIdle()

            assertEquals("w1", recutApi.lastPreviewWorkerId)
            assertEquals("2026-09-15", recutApi.lastPreviewFrom)
            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals(PREVIEW, loaded.preview)
            assertTrue(loaded.decisions.isEmpty())
            assertFalse(loaded.previewing)
        }

    @Test
    fun `a second preview while one is in flight is a no-op`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi(hangPreview = true)
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")

            viewModel.preview()
            dispatcher.scheduler.runCurrent()
            viewModel.preview()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, recutApi.previewCount)
            assertTrue((viewModel.state.value as WorkerRecutUiState.Loaded).previewing)
        }

    @Test
    fun `a bounds refusal on preview is shown verbatim and the input step stays`() =
        runTest(dispatcher) {
            val recutApi =
                FakeRecutApi(previewResult = RecutPreviewResult.Refused("Range starts before today.", "recut.from_before_today"))
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2020-01-01")

            viewModel.preview()
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals(BookingActionErrorUi.ServerRefusal("Range starts before today."), loaded.error)
            assertNull(loaded.preview)
            assertEquals("2020-01-01", loaded.from)
        }

    @Test
    fun `a transport failure on preview is not dressed up as a refusal`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")

            viewModel.preview()
            advanceUntilIdle()

            assertEquals(
                BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport),
                (viewModel.state.value as WorkerRecutUiState.Loaded).error,
            )
        }

    @Test
    fun `requestConfirm is a no-op until every decidable booking is decided`() =
        runTest(dispatcher) {
            val viewModel =
                WorkerRecutViewModel(FakeRecutApi(previewResult = RecutPreviewResult.Loaded(PREVIEW)), FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()

            viewModel.requestConfirm()
            assertFalse((viewModel.state.value as WorkerRecutUiState.Loaded).confirming)

            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            assertTrue((viewModel.state.value as WorkerRecutUiState.Loaded).confirming)
        }

    @Test
    fun `dismissConfirm closes the dialog without sending anything`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Loaded(PREVIEW))
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()
            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            viewModel.dismissConfirm()

            assertFalse((viewModel.state.value as WorkerRecutUiState.Loaded).confirming)
            assertEquals(0, recutApi.confirmCount)
        }

    @Test
    fun `confirming sends one decision per decidable booking and shows the result`() =
        runTest(dispatcher) {
            val recutApi =
                FakeRecutApi(
                    previewResult = RecutPreviewResult.Loaded(PREVIEW),
                    confirmResult = RecutConfirmResult.Confirmed(CONFIRMATION),
                )
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()
            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            viewModel.confirm()
            advanceUntilIdle()

            assertEquals("w1", recutApi.lastConfirmWorkerId)
            assertEquals("2026-09-15", recutApi.lastConfirmFrom)
            assertEquals(PREVIEW.fingerprint, recutApi.lastConfirmFingerprint)
            assertEquals(listOf(RecutBookingDecision("b1", RecutDecision.Cancel)), recutApi.lastConfirmDecisions)

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals(CONFIRMATION, loaded.result)
            assertNull(loaded.preview)
            assertTrue(loaded.decisions.isEmpty())
            assertFalse(loaded.confirming)
            assertFalse(loaded.busy)
        }

    @Test
    fun `recut stale clears the preview and decisions back to the input step`() =
        runTest(dispatcher) {
            val recutApi =
                FakeRecutApi(
                    previewResult = RecutPreviewResult.Loaded(PREVIEW),
                    confirmResult = RecutConfirmResult.Refused("Reload the preview and try again.", "recut.stale"),
                )
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()
            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            viewModel.confirm()
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertNull(loaded.preview)
            assertTrue(loaded.decisions.isEmpty())
            assertFalse(loaded.confirming)
            assertEquals(BookingActionErrorUi.ServerRefusal("Reload the preview and try again."), loaded.error)
            // The `from` the operator typed stays - only the stale preview is thrown away.
            assertEquals("2026-09-15", loaded.from)
        }

    @Test
    fun `recut day_changed_concurrently clears the preview the identical way stale does`() =
        runTest(dispatcher) {
            val recutApi =
                FakeRecutApi(
                    previewResult = RecutPreviewResult.Loaded(PREVIEW),
                    confirmResult = RecutConfirmResult.Refused("Some days already re-cut stand.", "recut.day_changed_concurrently"),
                )
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()
            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            viewModel.confirm()
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertNull(loaded.preview)
            assertTrue(loaded.decisions.isEmpty())
            assertEquals(BookingActionErrorUi.ServerRefusal("Some days already re-cut stand."), loaded.error)
        }

    @Test
    fun `every other confirm refusal keeps the preview and decisions exactly as they were`() =
        runTest(dispatcher) {
            val recutApi =
                FakeRecutApi(
                    previewResult = RecutPreviewResult.Loaded(PREVIEW),
                    confirmResult = RecutConfirmResult.Refused("A decision is missing.", "recut.missing_decision"),
                )
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()
            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            viewModel.confirm()
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals(PREVIEW, loaded.preview)
            assertEquals(mapOf("b1" to RecutDecision.Cancel), loaded.decisions)
            assertFalse(loaded.confirming)
            assertEquals(BookingActionErrorUi.ServerRefusal("A decision is missing."), loaded.error)
        }

    @Test
    fun `a transport failure on confirm is not dressed up as a refusal, and keeps the preview`() =
        runTest(dispatcher) {
            val recutApi =
                FakeRecutApi(
                    previewResult = RecutPreviewResult.Loaded(PREVIEW),
                    confirmResult = RecutConfirmResult.Failed(BookingsQueueFailure.Transport),
                )
            val viewModel = WorkerRecutViewModel(recutApi, FakeRecutBookingsApi(), dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()
            viewModel.decide("b1", RecutDecision.Cancel)
            viewModel.requestConfirm()

            viewModel.confirm()
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals(BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport), loaded.error)
            assertEquals(PREVIEW, loaded.preview)
        }

    @Test
    fun `revealing unmasks every booking sharing the same personId, across every day`() =
        runTest(dispatcher) {
            val runBookingOne = BOOKING_PENDING.copy(bookingId = "b1", personId = "p1", phone = "***1234", masked = true)
            val runBookingTwo = BOOKING_PENDING.copy(bookingId = "b2", personId = "p1", phone = "***1234", masked = true)
            val unrelatedBooking = BOOKING_PENDING.copy(bookingId = "b3", personId = "p2", phone = "***5678", masked = true)
            val preview =
                RecutPreview(
                    days =
                        listOf(
                            RecutDay("2026-09-15", 0, listOf(runBookingOne)),
                            RecutDay("2026-09-16", 0, listOf(runBookingTwo, unrelatedBooking)),
                        ),
                    fingerprint = "fp1",
                )
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Loaded(preview))
            val bookingsApi = FakeRecutBookingsApi(revealResult = RevealPhoneResult.Revealed("+70001234567"))
            val viewModel = WorkerRecutViewModel(recutApi, bookingsApi, dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()

            viewModel.reveal("p1")
            advanceUntilIdle()

            val loadedPreview = (viewModel.state.value as WorkerRecutUiState.Loaded).preview!!
            val bookingsByPersonOne = loadedPreview.days.flatMap { it.bookings }.filter { it.personId == "p1" }
            assertTrue(bookingsByPersonOne.all { it.phone == "+70001234567" && !it.masked })
            val unrelated = loadedPreview.days.flatMap { it.bookings }.single { it.bookingId == "b3" }
            assertEquals("***5678", unrelated.phone)
            assertTrue(unrelated.masked)
            assertEquals("p1", bookingsApi.lastCustomerId)
            assertEquals("AndroidRecut", bookingsApi.lastSurface)
        }

    @Test
    fun `a reveal refusal is shown verbatim and the masked value stays on screen`() =
        runTest(dispatcher) {
            val bookingWithPhone = BOOKING_PENDING.copy(personId = "p1", phone = "***1234", masked = true)
            val preview =
                RecutPreview(
                    days = listOf(RecutDay("2026-09-15", 0, listOf(bookingWithPhone))),
                    fingerprint = "fp1",
                )
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Loaded(preview))
            val bookingsApi = FakeRecutBookingsApi(revealResult = RevealPhoneResult.Refused("Not entitled to this customer's phone."))
            val viewModel = WorkerRecutViewModel(recutApi, bookingsApi, dispatcher)
            viewModel.open("w1", initialFrom = "2026-09-15")
            viewModel.preview()
            advanceUntilIdle()

            viewModel.reveal("p1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerRecutUiState.Loaded
            assertEquals(BookingActionErrorUi.ServerRefusal("Not entitled to this customer's phone."), loaded.error)
            val loadedPreview = loaded.preview!!
            val day = loadedPreview.days.single()
            val booking = day.bookings.single()
            assertEquals("***1234", booking.phone)
            assertTrue(booking.masked)
        }

    private companion object {
        val BOOKING_PENDING =
            RecutBooking(
                bookingId = "b1",
                startsAt = "2026-09-15T09:00:00Z",
                endsAt = "2026-09-15T09:30:00Z",
                status = RecutBookingStatus.PendingConfirmation,
                rawStatus = "PendingConfirmation",
                serviceId = null,
                serviceName = null,
                personId = null,
                phone = null,
                masked = false,
                canDecide = true,
            )
        val PREVIEW = RecutPreview(days = listOf(RecutDay("2026-09-15", 2, listOf(BOOKING_PENDING))), fingerprint = "fp1")
        val CONFIRMATION =
            RecutConfirmation(
                recutDays = listOf("2026-09-15"),
                skippedDays = emptyList(),
                slotsDeleted = 2,
                slotsInserted = 3,
                bookingsCancelled = 1,
            )
    }
}

/** The identical hand-written fake shape every sibling view model test in this package already uses. */
private class FakeRecutApi(
    private val previewResult: RecutPreviewResult = RecutPreviewResult.Loaded(RecutPreview(emptyList(), "fp")),
    private val confirmResult: RecutConfirmResult = RecutConfirmResult.NotConfigured,
    private val hangPreview: Boolean = false,
) : RecutApi {
    var previewCount: Int = 0
    var confirmCount: Int = 0
    var lastPreviewWorkerId: String? = null
    var lastPreviewFrom: String? = null
    var lastConfirmWorkerId: String? = null
    var lastConfirmFrom: String? = null
    var lastConfirmFingerprint: String? = null
    var lastConfirmDecisions: List<RecutBookingDecision>? = null

    override suspend fun preview(
        workerId: String,
        from: String,
    ): RecutPreviewResult {
        previewCount++
        lastPreviewWorkerId = workerId
        lastPreviewFrom = from
        if (hangPreview) awaitCancellation()
        return previewResult
    }

    override suspend fun confirm(
        workerId: String,
        from: String,
        fingerprint: String,
        decisions: List<RecutBookingDecision>,
    ): RecutConfirmResult {
        confirmCount++
        lastConfirmWorkerId = workerId
        lastConfirmFrom = from
        lastConfirmFingerprint = fingerprint
        lastConfirmDecisions = decisions
        return confirmResult
    }
}

/** The identical hand-written fake shape [ago.chat.android.bookings.WorkerSlotsViewModelTest]'s own
 * `FakeBookingsApi` already establishes - named differently here (`FakeRecutBookingsApi`) only because a
 * `private` top-level class name must be unique within its package's compiled module, not merely within
 * its own file, and this package already has one. Only [revealCustomerPhone] is exercised here, so every
 * other member throws to make an accidental call to it fail loudly. */
private class FakeRecutBookingsApi(
    private val revealResult: RevealPhoneResult = RevealPhoneResult.Revealed("+70000000000"),
) : BookingsApi {
    var lastCustomerId: String? = null
    var lastSurface: String? = null

    override suspend fun revealCustomerPhone(
        customerId: String,
        surface: String,
    ): RevealPhoneResult {
        lastCustomerId = customerId
        lastSurface = surface
        return revealResult
    }

    override suspend fun fetchPendingQueue() = throw UnsupportedOperationException()

    override suspend fun fetchConfirmedBookings(
        from: String,
        to: String,
    ) = throw UnsupportedOperationException()

    override suspend fun fetchContacts() = throw UnsupportedOperationException()

    override suspend fun rejectBooking(bookingId: String) = throw UnsupportedOperationException()

    override suspend fun cancelBooking(bookingId: String) = throw UnsupportedOperationException()

    override suspend fun markNoShow(bookingId: String) = throw UnsupportedOperationException()

    override suspend fun fetchServices() = throw UnsupportedOperationException()

    override suspend fun updateService(
        serviceId: String,
        name: String,
        durationMinutes: Int,
        priceMinorUnits: Int?,
        priceIsFrom: Boolean,
        description: String?,
        isActive: Boolean,
    ) = throw UnsupportedOperationException()

    override suspend fun fetchPhoneReveals(
        before: String?,
        limit: Int?,
    ) = throw UnsupportedOperationException()
}
