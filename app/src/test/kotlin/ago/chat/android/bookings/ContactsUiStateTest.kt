package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-269`: plain JVM tests for the two rules this item pulled out of [ContactsScreen.kt] so they could be
 * asserted without Compose — [filterContacts] (the search box's own match rule) and
 * [Contact.phoneNeedsAttention] (the warning-glyph visibility rule). Neither needs a `ContactsViewModel`
 * or a `StandardTestDispatcher`: both are bare functions over bare [Contact] values, the same restraint
 * [ContactsViewModelTest] already applies elsewhere in this package.
 */
class ContactsUiStateTest {
    @Test
    fun `a blank query returns every contact, unfiltered`() {
        val contacts = listOf(contact(name = "Анна"), contact(name = "Борис"))

        assertEquals(contacts, filterContacts(contacts, ""))
        assertEquals(contacts, filterContacts(contacts, "   "))
    }

    @Test
    fun `a query matching the name, case-insensitively, keeps that contact`() {
        val anna = contact(name = "Анна")
        val boris = contact(name = "Борис")

        assertEquals(listOf(anna), filterContacts(listOf(anna, boris), "анн"))
        assertEquals(listOf(anna), filterContacts(listOf(anna, boris), "АНН"))
    }

    @Test
    fun `a query matching the phone, not the name, keeps that contact`() {
        val anna = contact(name = "Анна", phone = "+7***1234")
        val boris = contact(name = "Борис", phone = "+7***5678")

        assertEquals(listOf(anna), filterContacts(listOf(anna, boris), "1234"))
    }

    @Test
    fun `a nameless contact is still findable by phone`() {
        val nameless = contact(name = null, phone = "+7***9999")

        assertEquals(listOf(nameless), filterContacts(listOf(nameless), "9999"))
    }

    @Test
    fun `a nameless contact never matches a name-shaped query`() {
        val nameless = contact(name = null, phone = "+7***9999")

        assertEquals(emptyList<Contact>(), filterContacts(listOf(nameless), "анна"))
    }

    @Test
    fun `a query matching nobody returns an empty list`() {
        val contacts = listOf(contact(name = "Анна"), contact(name = "Борис"))

        assertEquals(emptyList<Contact>(), filterContacts(contacts, "нет такого"))
    }

    @Test
    fun `the glyph is needed when neither verified nor operator-confirmed`() {
        assertTrue(contact(phoneVerifiedAt = null, phoneConfirmedByOperatorAt = null).phoneNeedsAttention)
    }

    @Test
    fun `the glyph is not needed once verified by SMS code alone`() {
        assertFalse(contact(phoneVerifiedAt = "2026-09-01T10:00:00Z", phoneConfirmedByOperatorAt = null).phoneNeedsAttention)
    }

    @Test
    fun `the glyph is not needed once confirmed by an operator alone`() {
        assertFalse(contact(phoneVerifiedAt = null, phoneConfirmedByOperatorAt = "2026-09-01T10:00:00Z").phoneNeedsAttention)
    }

    @Test
    fun `the glyph is not needed when both facts are present`() {
        assertFalse(
            contact(
                phoneVerifiedAt = "2026-09-01T10:00:00Z",
                phoneConfirmedByOperatorAt = "2026-09-02T10:00:00Z",
            ).phoneNeedsAttention,
        )
    }

    // `26-282` (A8): [ContactsFilter]'s own three-way predicate - a plain function over a bare [Contact],
    // the identical "pull the rule out so it is testable without Compose" reasoning this file's own class
    // doc comment already states for [filterContacts]/[Contact.phoneNeedsAttention].
    @Test
    fun `All matches every contact regardless of upcoming count`() {
        assertTrue(ContactsFilter.All.matches(contact(upcomingBookingCount = 0)))
        assertTrue(ContactsFilter.All.matches(contact(upcomingBookingCount = 3)))
    }

    @Test
    fun `HasUpcoming matches only a positive count`() {
        assertTrue(ContactsFilter.HasUpcoming.matches(contact(upcomingBookingCount = 1)))
        assertFalse(ContactsFilter.HasUpcoming.matches(contact(upcomingBookingCount = 0)))
    }

    @Test
    fun `NoUpcoming matches only a zero count`() {
        assertTrue(ContactsFilter.NoUpcoming.matches(contact(upcomingBookingCount = 0)))
        assertFalse(ContactsFilter.NoUpcoming.matches(contact(upcomingBookingCount = 1)))
    }

    @Test
    fun `visibleContacts applies the filter chip on top of the search query`() {
        val withBooking = contact(name = "Анна", upcomingBookingCount = 2)
        val withoutBooking = contact(name = "Борис", upcomingBookingCount = 0)
        val state =
            ContactsUiState.Loaded(
                contacts = listOf(withBooking, withoutBooking),
                filter = ContactsFilter.HasUpcoming,
            )

        assertEquals(listOf(withBooking), state.visibleContacts)
    }

    private fun contact(
        name: String? = "Анна",
        phone: String = "+7***5678",
        phoneVerifiedAt: String? = null,
        phoneConfirmedByOperatorAt: String? = null,
        upcomingBookingCount: Int = 0,
    ) = Contact(
        customerId = "c1",
        phone = phone,
        masked = true,
        displayName = name,
        noShowCount = 0,
        phoneVerifiedAt = phoneVerifiedAt,
        phoneConfirmedByOperatorAt = phoneConfirmedByOperatorAt,
        upcomingBookingCount = upcomingBookingCount,
    )
}
