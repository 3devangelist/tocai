package pt.tocai.app.rules

/**
 * Snapshot imutável das definições que as regras precisam.
 * Kotlin puro: sem dependências de Android, para poder ser testado na JVM.
 */
data class RuleSettings(
    val defaultOffsets: List<Int>,
    val autoCalendarIds: Set<Long>,
    val calendarOffsets: Map<Long, List<Int>>,
    val includeKeywords: List<String>,
    val excludeKeywords: List<String>,
    val skipDeclined: Boolean,
    val forcedOn: Set<String>,
    val forcedOff: Set<String>,
    /** Minutos antes próprios de um evento (CalendarEvent.key); têm prioridade sobre os do calendário. */
    val eventOffsets: Map<String, List<Int>> = emptyMap(),
)
