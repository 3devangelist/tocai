package pt.tocai.app.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import pt.tocai.app.data.SettingsStore
import java.util.Calendar
import java.util.TimeZone

class DailySyncTest {

    private lateinit var savedTz: TimeZone

    @Before
    fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedTz)
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    @Test
    fun beforeTime_sameDay() {
        assertEquals(at(2026, 11, 3, 7, 0), DailySync.nextTriggerAt(at(2026, 11, 3, 6, 30), 7, 0))
    }

    @Test
    fun atOrAfterTime_nextDay() {
        assertEquals(at(2026, 11, 4, 7, 0), DailySync.nextTriggerAt(at(2026, 11, 3, 7, 0), 7, 0))
        assertEquals(at(2026, 11, 4, 7, 0), DailySync.nextTriggerAt(at(2026, 11, 3, 7, 0, 5), 7, 0))
        assertEquals(at(2027, 1, 1, 7, 0), DailySync.nextTriggerAt(at(2026, 12, 31, 22, 0), 7, 0))
    }

    @Test
    fun acrossDstChange_keepsLocalTime() {
        // 29/03/2026: Lisboa passa das 01:00 para as 02:00
        assertEquals(at(2026, 3, 29, 7, 0), DailySync.nextTriggerAt(at(2026, 3, 28, 8, 0), 7, 0))
        // 25/10/2026: volta das 02:00 para a 01:00
        assertEquals(at(2026, 10, 25, 7, 0), DailySync.nextTriggerAt(at(2026, 10, 24, 8, 0), 7, 0))
    }

    @Test
    fun parseAndFormatTime() {
        assertEquals(7 to 5, SettingsStore.parseTime("07:05"))
        assertEquals(7 to 5, SettingsStore.parseTime(" 7:5 "))
        assertEquals(23 to 59, SettingsStore.parseTime("23:59"))
        assertNull(SettingsStore.parseTime("24:00"))
        assertNull(SettingsStore.parseTime("7"))
        assertNull(SettingsStore.parseTime(null))
        assertNull(SettingsStore.parseTime("ab:cd"))
        assertEquals("07:05", SettingsStore.formatTime(7, 5))
    }
}
