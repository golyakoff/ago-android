package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
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
 * `26-96`: the service dictionary's own read and its one write — the identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling view-model test in this package
 * already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModelTest {
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
            val api = FakeServicesApi(hangFetch = true)
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ServicesUiState.Loading, viewModel.state.value)
        }

    /** The whole argument for option (a), read from this side: an archived service is a row to render,
     * never a row to drop — the server keeps returning it precisely so a worker card and a past
     * booking can still resolve its name. */
    @Test
    fun `a withdrawn service arrives in the list like any other, marked`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(haircut(), colour(isActive = false))))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as ServicesUiState.Loaded
            assertEquals(2, loaded.services.size)
            assertFalse(loaded.services[1].isActive)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.NotConfigured)
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ServicesUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ServicesUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `editing prefills the draft from the row, kopecks converted back into rubles`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(colour())))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.edit(colour())

            val draft = (viewModel.state.value as ServicesUiState.Loaded).editing!!
            assertEquals("Colour", draft.name)
            assertEquals("90", draft.durationMinutes)
            // 350000 kopecks is 3500 rubles - never "350000", and never "3500.0".
            assertEquals("3500", draft.priceRubles)
            assertTrue(draft.priceIsFrom)
        }

    @Test
    fun `saving sends every field, converts rubles back to kopecks, and re-reads`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(haircut())))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.edit(haircut())
            viewModel.submit(haircut().toDraft().copy(durationMinutes = "60", priceRubles = "1500"))
            advanceUntilIdle()

            assertEquals(
                listOf(UpdateCall("s1", "Haircut", 60, 150000, false, null, true)),
                api.updateCalls,
            )
            // The authoritative answer is always the next read - never what this class assumed it wrote.
            assertEquals(2, api.fetchCalls)
            // And the form closes on the server's own fresh list, not optimistically before it.
            assertNull((viewModel.state.value as ServicesUiState.Loaded).editing)
        }

    /** «Снять с продажи» is [ServicesViewModel.submit] with the flag cleared, not a second code path —
     * so the row's own five fields must still travel, because the endpoint replaces the whole record. */
    @Test
    fun `withdrawing sends the row's own current fields beside the cleared flag`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(colour())))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.submit(colour().toDraft().copy(isActive = false))
            advanceUntilIdle()

            assertEquals(
                listOf(UpdateCall("s2", "Colour", 90, 350000, true, "Full colour and toner.", false)),
                api.updateCalls,
            )
        }

    @Test
    fun `clearing the price also clears the from flag, the way the domain would have anyway`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(colour())))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.submit(colour().toDraft().copy(priceRubles = "  "))
            advanceUntilIdle()

            val call = api.updateCalls.single()
            assertNull(call.priceMinorUnits)
            assertFalse(call.priceIsFrom)
        }

    @Test
    fun `a blank duration is refused here, with no request sent at all`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(haircut())))
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.submit(haircut().toDraft().copy(durationMinutes = " "))
            advanceUntilIdle()

            assertTrue(api.updateCalls.isEmpty())
            assertEquals(
                BookingActionErrorUi.InvalidDuration,
                (viewModel.state.value as ServicesUiState.Loaded).actionError,
            )
        }

    /** A duration the *server* refuses - zero - is sent, and its refusal is shown in the server's own
     * words. The client owns exactly one validation rule, and this is the proof it owns no more. */
    @Test
    fun `a server refusal is shown verbatim and leaves the typed edit on screen`() =
        runTest(dispatcher) {
            val api =
                FakeServicesApi(
                    result = ServicesResult.Loaded(listOf(haircut())),
                    updateResult = BookingActionResult.Refused("A service must take a positive amount of time."),
                )
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.edit(haircut())

            // Typed, then submitted - the order the form itself produces, since every keystroke goes
            // through `onDraftChanged` before the save button hands the same draft back.
            val typed = haircut().toDraft().copy(durationMinutes = "0")
            viewModel.onDraftChanged(typed)
            viewModel.submit(typed)
            advanceUntilIdle()

            assertEquals(1, api.updateCalls.size)
            assertEquals(0, api.updateCalls.single().durationMinutes)
            val loaded = viewModel.state.value as ServicesUiState.Loaded
            assertEquals(
                BookingActionErrorUi.ServerRefusal("A service must take a positive amount of time."),
                loaded.actionError,
            )
            // The form stays open with what was typed - blanking it would throw away the very edit the
            // operator now has to fix.
            assertEquals("0", loaded.editing!!.durationMinutes)
        }

    @Test
    fun `a second write on the same service while one is in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeServicesApi(result = ServicesResult.Loaded(listOf(haircut())), hangUpdate = true)
            val viewModel = ServicesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.submit(haircut().toDraft().copy(isActive = false))
            dispatcher.scheduler.runCurrent()
            viewModel.submit(haircut().toDraft().copy(isActive = false))
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.updateCalls.size)
        }

    private fun haircut() =
        ConfiguredService(
            serviceId = "s1",
            name = "Haircut",
            durationMinutes = 45,
            priceMinorUnits = null,
            priceCurrencyCode = null,
            priceIsFrom = false,
            description = null,
            isActive = true,
        )

    private fun colour(isActive: Boolean = true) =
        ConfiguredService(
            serviceId = "s2",
            name = "Colour",
            durationMinutes = 90,
            priceMinorUnits = 350000,
            priceCurrencyCode = "RUB",
            priceIsFrom = true,
            description = "Full colour and toner.",
            isActive = isActive,
        )

    /** What one `PUT /services/{id}` actually carried - recorded whole, because "replace semantics"
     * is a claim about every field, not only the one the caller meant to change. */
    private data class UpdateCall(
        val serviceId: String,
        val name: String,
        val durationMinutes: Int,
        val priceMinorUnits: Int?,
        val priceIsFrom: Boolean,
        val description: String?,
        val isActive: Boolean,
    )

    private class FakeServicesApi(
        var result: ServicesResult = ServicesResult.NotConfigured,
        private val hangFetch: Boolean = false,
        var updateResult: BookingActionResult = BookingActionResult.Succeeded,
        private val hangUpdate: Boolean = false,
    ) : BookingsApi {
        var fetchCalls: Int = 0
            private set
        val updateCalls: MutableList<UpdateCall> = mutableListOf()

        override suspend fun fetchServices(): ServicesResult {
            fetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        override suspend fun updateService(
            serviceId: String,
            name: String,
            durationMinutes: Int,
            priceMinorUnits: Int?,
            priceIsFrom: Boolean,
            description: String?,
            isActive: Boolean,
        ): BookingActionResult {
            updateCalls.add(
                UpdateCall(serviceId, name, durationMinutes, priceMinorUnits, priceIsFrom, description, isActive),
            )
            if (hangUpdate) awaitCancellation()
            return updateResult
        }

        // `ServicesViewModel` reads and writes the dictionary alone - never the queue, the confirmed
        // list, the customer base, or any veto write.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException("not used by this class")
    }
}
