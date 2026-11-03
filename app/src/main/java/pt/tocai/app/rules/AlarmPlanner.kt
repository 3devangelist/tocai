package pt.tocai.app.rules

import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.PlannedAlarm
import java.text.Normalizer
import java.util.Locale

/** Decide que eventos têm despertador e quando tocam. Kotlin puro (sem Android). */
object AlarmPlanner {

    private const val MINUTE_MS = 60_000L
    private const val MAX_OFFSET = 1440
    private val FALLBACK_OFFSETS = listOf(5)
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** O que as regras automáticas decidem, ignorando forcedOn/forcedOff. */
    fun isAutoEnabled(event: CalendarEvent, rs: RuleSettings): Boolean {
        if (event.allDay) return false
        if (event.declined && rs.skipDeclined) return false
        if (event.calendarId !in rs.autoCalendarIds) return false

        val title = normalize(event.title)
        val excludes = cleanKeywords(rs.excludeKeywords)
        if (excludes.any { title.contains(it) }) return false

        val includes = cleanKeywords(rs.includeKeywords)
        if (includes.isNotEmpty() && includes.none { title.contains(it) }) return false

        return true
    }

    /** Decisão final: forcedOff -> false; forcedOn -> true (mesmo noutros calendários; dia inteiro continua false); senão isAutoEnabled. */
    fun isEnabled(event: CalendarEvent, rs: RuleSettings): Boolean {
        if (event.allDay) return false
        val key = event.key
        if (key in rs.forcedOff) return false
        if (key in rs.forcedOn) return true
        return isAutoEnabled(event, rs)
    }

    /** Minutos antes para este evento: os do calendário (se definidos e válidos) ou os por defeito. */
    fun offsetsFor(event: CalendarEvent, rs: RuleSettings): List<Int> {
        val perCalendar = sanitizeOffsets(rs.calendarOffsets[event.calendarId])
        if (perCalendar.isNotEmpty()) return perCalendar
        val defaults = sanitizeOffsets(rs.defaultOffsets)
        return defaults.ifEmpty { FALLBACK_OFFSETS }
    }

    /** Despertadores com triggerAt > now para eventos ativos, ordenados por triggerAt. */
    fun plan(events: List<CalendarEvent>, rs: RuleSettings, now: Long): List<PlannedAlarm> {
        val result = ArrayList<PlannedAlarm>()
        val seen = HashSet<String>()
        for (event in events) {
            if (!isEnabled(event, rs)) continue
            for (offset in offsetsFor(event, rs)) {
                val triggerAt = event.begin - offset * MINUTE_MS
                if (triggerAt <= now) continue
                val alarm = PlannedAlarm(event, triggerAt, offset)
                // evita duplicados se o mesmo evento vier repetido na lista
                if (seen.add(alarm.alarmKey)) result.add(alarm)
            }
        }
        return result.sortedWith(
            compareBy<PlannedAlarm>({ it.triggerAt }, { it.event.begin }, { it.alarmKey })
        )
    }

    /** Minúsculas e sem acentos, para comparar palavras-chave. */
    internal fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return COMBINING_MARKS.replace(decomposed, "").lowercase(Locale.ROOT).trim()
    }

    private fun cleanKeywords(keywords: List<String>): List<String> =
        keywords.map { normalize(it) }.filter { it.isNotEmpty() }

    private fun sanitizeOffsets(offsets: List<Int>?): List<Int> =
        offsets.orEmpty().filter { it in 0..MAX_OFFSET }.distinct().sortedDescending()
}
