package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.Contact
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
import org.junit.Before
import org.junit.Test

/**
 * `26-52`: the plain one-shot read — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [BookingsViewModelTest]/[ConfirmedBookingsViewModelTest] already establish for their own sibling view
 * models.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
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
            val api = FakeBookingsApi(hangFetch = true)
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ContactsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded list passes straight through, unedited`() =
        runTest(dispatcher) {
            val contact =
                Contact(
                    customerId = "c1",
                    phone = "+7***5678",
                    masked = true,
                    displayName = "Анна",
                    noShowCount = 3,
                    phoneVerifiedAt = "2026-09-01T10:00:00Z",
                    phoneConfirmedByOperatorAt = null,
                )
            val api = FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact)))
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ContactsUiState.Loaded(listOf(contact)), viewModel.state.value)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ContactsResult.NotConfigured)
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ContactsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ContactsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ContactsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ContactsResult.Failed(BookingsQueueFailure.Unexpected))
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.contactsFetchCalls)

            api.result = ContactsResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.contactsFetchCalls)
            assertEquals(ContactsUiState.Loaded(emptyList()), viewModel.state.value)
        }

    @Test
    fun `revealing a customer marks it, immediately, before the server answers`() =
        runTest(dispatcher) {
            val contact = contact(id = "c1")
            val api = FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact)), hangReveal = true)
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")

            assertEquals(
                ContactsUiState.Loaded(listOf(contact), revealingCustomerIds = setOf("c1")),
                viewModel.state.value,
            )
        }

    @Test
    fun `revealing the same customer twice sends exactly one server call`() =
        runTest(dispatcher) {
            val contact = contact(id = "c1")
            val api = FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact)), hangReveal = true)
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            dispatcher.scheduler.runCurrent()
            viewModel.reveal("c1")
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.revealCalls.size)
        }

    @Test
    fun `revealing a different customer proceeds independently while the first is still in flight`() =
        runTest(dispatcher) {
            val first = contact(id = "c1")
            val second = contact(id = "c2")
            val api = FakeBookingsApi(result = ContactsResult.Loaded(listOf(first, second)), hangReveal = true)
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            viewModel.reveal("c2")

            // `docs/backlog/26-53-*.md`'s own Scope item 4: "one reveal in flight per customer" - not one
            // reveal for the whole screen, so a second, genuinely different customer stays tappable.
            assertEquals(
                ContactsUiState.Loaded(listOf(first, second), revealingCustomerIds = setOf("c1", "c2")),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful reveal replaces the phone on every row for that customer`() =
        runTest(dispatcher) {
            val firstBooking = contact(id = "c1")
            val other = contact(id = "c2")
            val api =
                FakeBookingsApi(result = ContactsResult.Loaded(listOf(firstBooking, other))).apply {
                    revealResult = RevealPhoneResult.Revealed("+79991234567")
                }
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            advanceUntilIdle()

            assertEquals(listOf("c1" to BookingRevealSurface.ANDROID_CONTACTS), api.revealCalls)
            assertEquals(
                ContactsUiState.Loaded(
                    listOf(firstBooking.copy(phone = "+79991234567", masked = false), other),
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful reveal replaces every row sharing that customer id, never just the first`() =
        runTest(dispatcher) {
            // `docs/backlog/26-53-*.md`'s own Scope item 4: "a customer can have several pending
            // bookings" - Клиенты itself is one row per customer, but the replacement is written as a
            // plain `map` over every matching row, not "the first match", so this proves that shape
            // directly rather than only via a screen that happens never to hand it duplicates today.
            val firstRow = contact(id = "c1")
            val secondRow = contact(id = "c1")
            val api =
                FakeBookingsApi(result = ContactsResult.Loaded(listOf(firstRow, secondRow))).apply {
                    revealResult = RevealPhoneResult.Revealed("+79991234567")
                }
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            advanceUntilIdle()

            val unmasked = firstRow.copy(phone = "+79991234567", masked = false)
            assertEquals(ContactsUiState.Loaded(listOf(unmasked, unmasked)), viewModel.state.value)
        }

    @Test
    fun `a refusal leaves the masked value in place and shows the server's own detail`() =
        runTest(dispatcher) {
            val contact = contact(id = "c1")
            val api =
                FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact))).apply {
                    revealResult = RevealPhoneResult.Refused("Недостаточно прав для просмотра номера.")
                }
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            advanceUntilIdle()

            assertEquals(
                ContactsUiState.Loaded(
                    listOf(contact),
                    actionError = BookingActionErrorUi.ServerRefusal("Недостаточно прав для просмотра номера."),
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `a transport failure leaves the masked value in place and renders Unavailable`() =
        runTest(dispatcher) {
            val contact = contact(id = "c1")
            val api =
                FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact))).apply {
                    revealResult = RevealPhoneResult.Failed(BookingsQueueFailure.Transport)
                }
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            advanceUntilIdle()

            assertEquals(
                ContactsUiState.Loaded(listOf(contact), actionError = BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport)),
                viewModel.state.value,
            )
        }

    @Test
    fun `starting a new reveal clears a previously shown error, immediately`() =
        runTest(dispatcher) {
            // `CalendarQueuePage.tsx`'s own `handleReveal` clears its error the moment a new reveal
            // starts, before the request is even sent - a different moment than the veto actions' own
            // `act`, which only clears it via a successful reload. This proves the earlier moment: the
            // second `reveal` call below clears the error synchronously, before its own coroutine has
            // even been scheduled to run, let alone answered.
            val contact = contact(id = "c1")
            val api = FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact)))
            api.revealResult = RevealPhoneResult.Refused("на секунду опоздали")
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.reveal("c1")
            advanceUntilIdle()
            assertEquals(
                ContactsUiState.Loaded(listOf(contact), actionError = BookingActionErrorUi.ServerRefusal("на секунду опоздали")),
                viewModel.state.value,
            )

            viewModel.reveal("c1")

            assertEquals(
                ContactsUiState.Loaded(listOf(contact), revealingCustomerIds = setOf("c1"), actionError = null),
                viewModel.state.value,
            )
        }

    private fun contact(id: String) =
        Contact(
            customerId = id,
            phone = "+7***5678",
            masked = true,
            displayName = "Анна",
            noShowCount = 0,
            phoneVerifiedAt = null,
            phoneConfirmedByOperatorAt = null,
        )

    private class FakeBookingsApi(
        var result: ContactsResult = ContactsResult.NotConfigured,
        private val hangFetch: Boolean = false,
        var revealResult: RevealPhoneResult = RevealPhoneResult.Revealed("+79991234567"),
        private val hangReveal: Boolean = false,
    ) : BookingsApi {
        var contactsFetchCalls: Int = 0
            private set
        val revealCalls: MutableList<Pair<String, String>> = mutableListOf()

        // `26-52`'s own [ContactsViewModel] never calls either sibling read.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchContacts(): ContactsResult {
            contactsFetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        // `26-49` widened `BookingsApi` with three veto-write methods this class has no test of its own
        // for - `ContactsViewModel` reads Клиенты, never writes to Ожидают.
        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult {
            revealCalls.add(customerId to surface)
            if (hangReveal) awaitCancellation()
            return revealResult
        }

        // `26-74` widened `BookingsApi` with the reveal audit trail - `ContactsViewModel` performs a
        // reveal, it never reads the trail of ones already performed.
        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException("not used by this class")

        // `26-96` widened `BookingsApi` with the service dictionary and its edit - neither of which
        // this class reads or writes.
        override suspend fun fetchServices(): ServicesResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun updateService(
            serviceId: String,
            name: String,
            durationMinutes: Int,
            priceMinorUnits: Int?,
            priceIsFrom: Boolean,
            description: String?,
            isActive: Boolean,
        ): BookingActionResult = throw UnsupportedOperationException("not used by this class")
    }
}
