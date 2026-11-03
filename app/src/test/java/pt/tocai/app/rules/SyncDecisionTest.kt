package pt.tocai.app.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.PlannedAlarm

class SyncDecisionTest {

    private val transient: (String) -> Boolean = { it == "test" || it.startsWith("snooze:") }

    private fun alarm(id: Long, begin: Long, offset: Int) = PlannedAlarm(
        event = CalendarEvent(id, begin, begin + 1, "t", "", "", 1L, "", 0, allDay = false, declined = false),
        triggerAt = begin - offset * 60_000L,
        offsetMinutes = offset,
    )

    @Test
    fun leituraFalhadaNaoCancelaNada() {
        val scheduled = setOf("1_100@5", "2_200@15", "snooze:1_100@5")
        assertTrue(SyncDecision.keysToCancel(scheduled, null, transient).isEmpty())
    }

    @Test
    fun calendarioVazioCancelaTudoMenosTransitorios() {
        val scheduled = setOf("1_100@5", "2_200@15", "snooze:1_100@5", "test")
        assertEquals(setOf("1_100@5", "2_200@15"), SyncDecision.keysToCancel(scheduled, emptyList(), transient))
    }

    @Test
    fun cancelaSoOsQueSairamDoPlano() {
        val scheduled = setOf("1_100@5", "2_200@15")
        val plan = listOf(alarm(1, 100, 5), alarm(3, 300, 0))
        assertEquals(setOf("2_200@15"), SyncDecision.keysToCancel(scheduled, plan, transient))
    }
}
