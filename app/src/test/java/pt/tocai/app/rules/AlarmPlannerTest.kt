package pt.tocai.app.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tocai.app.data.CalendarEvent

class AlarmPlannerTest {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L
    private val consultCal = 1L
    private val otherCal = 2L

    private fun event(
        id: Long = 1L,
        title: String = "Consultoria EVOLT",
        beginInMinutes: Long = 60,
        calendarId: Long = consultCal,
        allDay: Boolean = false,
        declined: Boolean = false,
    ): CalendarEvent {
        val begin = now + beginInMinutes * minute
        return CalendarEvent(
            eventId = id, begin = begin, end = begin + 60 * minute,
            title = title, location = "", description = "",
            calendarId = calendarId, calendarName = "Cal $calendarId", color = 0,
            allDay = allDay, declined = declined,
        )
    }

    private fun settings(
        defaultOffsets: List<Int> = listOf(5),
        autoCalendarIds: Set<Long> = setOf(consultCal),
        calendarOffsets: Map<Long, List<Int>> = emptyMap(),
        includeKeywords: List<String> = emptyList(),
        excludeKeywords: List<String> = emptyList(),
        skipDeclined: Boolean = true,
        forcedOn: Set<String> = emptySet(),
        forcedOff: Set<String> = emptySet(),
        eventOffsets: Map<String, List<Int>> = emptyMap(),
    ) = RuleSettings(
        defaultOffsets, autoCalendarIds, calendarOffsets, includeKeywords,
        excludeKeywords, skipDeclined, forcedOn, forcedOff, eventOffsets,
    )

    @Test
    fun eventoNormalNumCalendarioAutoFicaAtivo() {
        assertTrue(AlarmPlanner.isAutoEnabled(event(), settings()))
        assertTrue(AlarmPlanner.isEnabled(event(), settings()))
    }

    @Test
    fun diaInteiroNuncaTemDespertador() {
        val e = event(allDay = true)
        assertFalse(AlarmPlanner.isAutoEnabled(e, settings()))
        assertFalse(AlarmPlanner.isEnabled(e, settings(forcedOn = setOf(e.key))))
        assertTrue(AlarmPlanner.plan(listOf(e), settings(forcedOn = setOf(e.key)), now).isEmpty())
    }

    @Test
    fun recusadoRespeitaSkipDeclined() {
        val e = event(declined = true)
        assertFalse(AlarmPlanner.isAutoEnabled(e, settings(skipDeclined = true)))
        assertTrue(AlarmPlanner.isAutoEnabled(e, settings(skipDeclined = false)))
    }

    @Test
    fun calendarioNaoAutoFicaDesligado() {
        val e = event(calendarId = otherCal)
        assertFalse(AlarmPlanner.isAutoEnabled(e, settings()))
        assertFalse(AlarmPlanner.isEnabled(e, settings()))
        assertFalse(AlarmPlanner.isAutoEnabled(event(), settings(autoCalendarIds = emptySet())))
    }

    @Test
    fun excludeIgnoraAcentosEMaiusculas() {
        val e = event(title = "Reunião INTERNA de equipa")
        assertFalse(AlarmPlanner.isAutoEnabled(e, settings(excludeKeywords = listOf("reuniao"))))
        assertFalse(AlarmPlanner.isAutoEnabled(e, settings(excludeKeywords = listOf("Interna"))))
        assertTrue(AlarmPlanner.isAutoEnabled(e, settings(excludeKeywords = listOf("almoço"))))
        // palavras vazias são ignoradas
        assertTrue(AlarmPlanner.isAutoEnabled(e, settings(excludeKeywords = listOf("", "  "))))
    }

    @Test
    fun includeIgnoraAcentosEMaiusculas() {
        val e = event(title = "SESSÃO de Consultadoria")
        assertTrue(AlarmPlanner.isAutoEnabled(e, settings(includeKeywords = listOf("sessao"))))
        assertTrue(AlarmPlanner.isAutoEnabled(e, settings(includeKeywords = listOf("xpto", "CONSULTADORIA"))))
        assertFalse(AlarmPlanner.isAutoEnabled(e, settings(includeKeywords = listOf("formação"))))
        // include vazio = todos
        assertTrue(AlarmPlanner.isAutoEnabled(e, settings(includeKeywords = emptyList())))
    }

    @Test
    fun excludeGanhaAoInclude() {
        val e = event(title = "Consultoria cancelada")
        val rs = settings(includeKeywords = listOf("consultoria"), excludeKeywords = listOf("CANCELADA"))
        assertFalse(AlarmPlanner.isAutoEnabled(e, rs))
    }

    @Test
    fun forcedOnFuncionaNoutroCalendario() {
        val e = event(calendarId = otherCal)
        val rs = settings(forcedOn = setOf(e.key))
        assertFalse(AlarmPlanner.isAutoEnabled(e, rs))
        assertTrue(AlarmPlanner.isEnabled(e, rs))
        val plan = AlarmPlanner.plan(listOf(e), rs, now)
        assertEquals(1, plan.size)
        assertEquals(e.begin - 5 * minute, plan[0].triggerAt)
    }

    @Test
    fun forcedOnIgnoraRecusado() {
        val e = event(declined = true)
        assertTrue(AlarmPlanner.isEnabled(e, settings(forcedOn = setOf(e.key))))
    }

    @Test
    fun forcedOffDesligaEventoAuto() {
        val e = event()
        val rs = settings(forcedOff = setOf(e.key))
        assertTrue(AlarmPlanner.isAutoEnabled(e, rs))
        assertFalse(AlarmPlanner.isEnabled(e, rs))
        assertTrue(AlarmPlanner.plan(listOf(e), rs, now).isEmpty())
        // forcedOff prevalece sobre forcedOn
        assertFalse(AlarmPlanner.isEnabled(e, settings(forcedOn = setOf(e.key), forcedOff = setOf(e.key))))
    }

    @Test
    fun offsetsPorCalendario() {
        val rs = settings(
            defaultOffsets = listOf(5),
            autoCalendarIds = setOf(consultCal, otherCal),
            calendarOffsets = mapOf(otherCal to listOf(10)),
        )
        assertEquals(listOf(5), AlarmPlanner.offsetsFor(event(calendarId = consultCal), rs))
        assertEquals(listOf(10), AlarmPlanner.offsetsFor(event(calendarId = otherCal), rs))
        // lista vazia no calendário = usa o defeito
        val rsVazio = settings(calendarOffsets = mapOf(consultCal to emptyList()))
        assertEquals(listOf(5), AlarmPlanner.offsetsFor(event(), rsVazio))
    }

    @Test
    fun offsetsSaoLimposEComFallback() {
        val rs = settings(defaultOffsets = listOf(5, 15, 5, -3, 2000, 0))
        assertEquals(listOf(15, 5, 0), AlarmPlanner.offsetsFor(event(), rs))
        assertEquals(listOf(5), AlarmPlanner.offsetsFor(event(), settings(defaultOffsets = emptyList())))
    }

    @Test
    fun multiplosOffsetsGeramVariosDespertadores() {
        val e = event(beginInMinutes = 60)
        val plan = AlarmPlanner.plan(listOf(e), settings(defaultOffsets = listOf(5, 15)), now)
        assertEquals(2, plan.size)
        assertEquals(15, plan[0].offsetMinutes)
        assertEquals(e.begin - 15 * minute, plan[0].triggerAt)
        assertEquals(5, plan[1].offsetMinutes)
        assertEquals(e.begin - 5 * minute, plan[1].triggerAt)
        assertEquals("${e.key}@15", plan[0].alarmKey)
    }

    @Test
    fun triggerAtNoPassadoFicaDeFora() {
        // começa daqui a 10 min: o de 15 min já passou, o de 5 min não
        val e = event(beginInMinutes = 10)
        val plan = AlarmPlanner.plan(listOf(e), settings(defaultOffsets = listOf(15, 5)), now)
        assertEquals(listOf(5), plan.map { it.offsetMinutes })
        // triggerAt exatamente igual a now também fica de fora
        val exato = event(id = 2, beginInMinutes = 5)
        assertTrue(AlarmPlanner.plan(listOf(exato), settings(), now).isEmpty())
        // evento já começado
        val passado = event(id = 3, beginInMinutes = -30)
        assertTrue(AlarmPlanner.plan(listOf(passado), settings(), now).isEmpty())
    }

    @Test
    fun planoOrdenadoPorTriggerAt() {
        val tarde = event(id = 1, title = "Tarde", beginInMinutes = 300)
        val cedo = event(id = 2, title = "Cedo", beginInMinutes = 30)
        val meio = event(id = 3, title = "Meio", beginInMinutes = 120)
        val desligado = event(id = 4, title = "Outro", beginInMinutes = 60, calendarId = otherCal)
        val plan = AlarmPlanner.plan(
            listOf(tarde, desligado, cedo, meio),
            settings(defaultOffsets = listOf(15, 5)),
            now,
        )
        assertEquals(6, plan.size)
        assertEquals(plan.map { it.triggerAt }.sorted(), plan.map { it.triggerAt })
        assertEquals(listOf(2L, 2L, 3L, 3L, 1L, 1L), plan.map { it.event.eventId })
        assertFalse(plan.any { it.event.eventId == 4L })
    }

    @Test
    fun offsetsPorEventoTemPrioridade() {
        val e = event(id = 7L, calendarId = otherCal)
        val outro = event(id = 8L, calendarId = otherCal)
        val rs = settings(
            defaultOffsets = listOf(5),
            calendarOffsets = mapOf(otherCal to listOf(10)),
            eventOffsets = mapOf(e.key to listOf(5, 30, 30, -1)),
        )
        assertEquals(listOf(30, 5), AlarmPlanner.offsetsFor(e, rs))
        // os outros eventos do mesmo calendário não mudam
        assertEquals(listOf(10), AlarmPlanner.offsetsFor(outro, rs))
        // lista vazia ou inválida no evento = usa o calendário
        val rsInvalido = settings(
            calendarOffsets = mapOf(otherCal to listOf(10)),
            eventOffsets = mapOf(e.key to listOf(-5, 5000)),
        )
        assertEquals(listOf(10), AlarmPlanner.offsetsFor(e, rsInvalido))
    }

    @Test
    fun offsetsPorEventoNoPlano() {
        // evento escolhido à mão noutro calendário, com 20 min antes
        val e = event(id = 9L, calendarId = otherCal, beginInMinutes = 60)
        val rs = settings(forcedOn = setOf(e.key), eventOffsets = mapOf(e.key to listOf(20)))
        val plan = AlarmPlanner.plan(listOf(e), rs, now)
        assertEquals(1, plan.size)
        assertEquals(20, plan[0].offsetMinutes)
        assertEquals(e.begin - 20 * minute, plan[0].triggerAt)
        // sem estar ligado, os minutos do evento não criam despertadores
        assertTrue(AlarmPlanner.plan(listOf(e), settings(eventOffsets = mapOf(e.key to listOf(20))), now).isEmpty())
    }
}
