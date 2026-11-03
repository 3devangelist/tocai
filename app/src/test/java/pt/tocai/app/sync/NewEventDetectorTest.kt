package pt.tocai.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tocai.app.data.CalendarEvent

class NewEventDetectorTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private val horizon = now + 7 * day

    private fun ev(id: Long, begin: Long, allDay: Boolean = false, declined: Boolean = false) = CalendarEvent(
        eventId = id, begin = begin, end = begin + hour,
        title = "Evento $id", location = "", description = "",
        calendarId = 1, calendarName = "Trabalho", color = 0,
        allDay = allDay, declined = declined,
    )

    private val noAlarm: (CalendarEvent) -> Boolean = { false }

    @Test
    fun primeiraExecucao_soRegista() {
        val events = listOf(ev(1, now + hour), ev(2, now + 2 * hour))
        val r = NewEventDetector.detect(events, null, 0L, now, noAlarm)
        assertTrue(r.toAnnounce.isEmpty())
        assertEquals(events.map { it.key }.toSet(), r.seen)

        // conjunto existente mas sem horizonte: também só regista
        val r2 = NewEventDetector.detect(events, emptySet(), 0L, now, noAlarm)
        assertTrue(r2.toAnnounce.isEmpty())
    }

    @Test
    fun eventoNovo_eAnunciado() {
        val old = ev(1, now + hour)
        val fresh = ev(2, now + 3 * hour)
        val r = NewEventDetector.detect(listOf(old, fresh), setOf(old.key), horizon, now, noAlarm)
        assertEquals(listOf(fresh), r.toAnnounce)
        assertEquals(setOf(old.key, fresh.key), r.seen)
    }

    @Test
    fun filtros_diaInteiro_passado_recusado_comDespertador() {
        val allDay = ev(1, now + day, allDay = true)
        val past = ev(2, now - hour)
        val declined = ev(3, now + hour, declined = true)
        val withAlarm = ev(4, now + 2 * hour)
        val ok = ev(5, now + 4 * hour)
        val r = NewEventDetector.detect(
            listOf(allDay, past, declined, withAlarm, ok), emptySet(), horizon, now,
        ) { it.eventId == 4L }
        assertEquals(listOf(ok), r.toAnnounce)
        // todos ficam registados como vistos
        assertEquals(5, r.seen.size)
    }

    @Test
    fun eventoQueSoEntraNaJanela_naoEAnunciado() {
        val prevHorizon = now + 7 * day - hour
        val edge = ev(1, now + 7 * day - 1)          // depois do horizonte anterior
        val inside = ev(2, now + 6 * day)
        val r = NewEventDetector.detect(listOf(edge, inside), emptySet(), prevHorizon, now, noAlarm)
        assertEquals(listOf(inside), r.toAnnounce)
        assertTrue(edge.key in r.seen)
    }

    @Test
    fun podaChavesAntigas_eOrdenaPorInicio() {
        val stale = "99_${now - 2 * day}"
        val recent = "98_${now - hour}"
        val a = ev(1, now + 5 * hour)
        val b = ev(2, now + hour)
        val r = NewEventDetector.detect(listOf(a, b), setOf(stale, recent, "lixo"), horizon, now, noAlarm)
        assertEquals(listOf(b, a), r.toAnnounce)
        assertTrue(recent in r.seen)
        assertTrue(stale !in r.seen)
        assertTrue("lixo" !in r.seen)
    }

    @Test
    fun jaVisto_naoRepete() {
        val a = ev(1, now + hour)
        val first = NewEventDetector.detect(listOf(a), emptySet(), horizon, now, noAlarm)
        assertEquals(listOf(a), first.toAnnounce)
        val second = NewEventDetector.detect(listOf(a), first.seen, horizon, now + 60_000, noAlarm)
        assertTrue(second.toAnnounce.isEmpty())
    }
}
