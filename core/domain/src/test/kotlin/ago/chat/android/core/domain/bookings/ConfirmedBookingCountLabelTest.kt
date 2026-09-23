package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmedBookingCountLabelTest {
    @Test
    fun `one is the singular form`() {
        assertEquals("1 запись", confirmedBookingsCountLabel(1))
        assertEquals("21 запись", confirmedBookingsCountLabel(21))
        assertEquals("101 запись", confirmedBookingsCountLabel(101))
    }

    @Test
    fun `two through four are the few form`() {
        assertEquals("2 записи", confirmedBookingsCountLabel(2))
        assertEquals("3 записи", confirmedBookingsCountLabel(3))
        assertEquals("4 записи", confirmedBookingsCountLabel(4))
        assertEquals("22 записи", confirmedBookingsCountLabel(22))
        assertEquals("104 записи", confirmedBookingsCountLabel(104))
    }

    @Test
    fun `five through twenty, and everything ending 0 or 5-9, are the many form`() {
        assertEquals("0 записей", confirmedBookingsCountLabel(0))
        assertEquals("5 записей", confirmedBookingsCountLabel(5))
        assertEquals("9 записей", confirmedBookingsCountLabel(9))
        assertEquals("10 записей", confirmedBookingsCountLabel(10))
        assertEquals("25 записей", confirmedBookingsCountLabel(25))
        assertEquals("100 записей", confirmedBookingsCountLabel(100))
    }

    @Test
    fun `eleven through fourteen are the many form even though they end in 1 through 4`() {
        assertEquals("11 записей", confirmedBookingsCountLabel(11))
        assertEquals("12 записей", confirmedBookingsCountLabel(12))
        assertEquals("14 записей", confirmedBookingsCountLabel(14))
        assertEquals("111 записей", confirmedBookingsCountLabel(111))
        assertEquals("112 записей", confirmedBookingsCountLabel(112))
    }
}
