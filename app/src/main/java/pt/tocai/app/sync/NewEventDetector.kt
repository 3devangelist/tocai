package pt.tocai.app.sync

import pt.tocai.app.data.CalendarEvent

/**
 * Lógica pura (testável na JVM) para detetar marcações novas no calendário.
 *
 * Guarda-se o conjunto das chaves já vistas e o fim da janela da verificação anterior ("horizonte").
 * Um evento só conta como novo se a sua chave nunca foi vista **e** começa antes do horizonte anterior;
 * eventos que só agora entram na janela (porque o tempo avançou ou a antecedência aumentou) são
 * registados sem aviso.
 */
object NewEventDetector {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    data class Result(
        /** Eventos a anunciar, por ordem de início. */
        val toAnnounce: List<CalendarEvent>,
        /** Novo conjunto de chaves vistas (podado). */
        val seen: Set<String>,
    )

    /**
     * @param events eventos da janela atual
     * @param seen chaves já vistas; null = primeira execução (só regista)
     * @param previousHorizon fim da janela anterior; <= 0 = primeira execução (só regista)
     * @param hasAlarm true se o evento já tem despertador
     */
    fun detect(
        events: List<CalendarEvent>,
        seen: Set<String>?,
        previousHorizon: Long,
        now: Long,
        hasAlarm: (CalendarEvent) -> Boolean,
    ): Result {
        val currentKeys = events.map { it.key }
        if (seen == null || previousHorizon <= 0L) {
            return Result(emptyList(), currentKeys.toSet())
        }
        val kept = seen.filter { keyBegin(it)?.let { b -> b >= now - DAY_MS } ?: false }
        val announce = events
            .asSequence()
            .filter { it.key !in seen }
            .filter { !it.allDay && !it.declined }
            .filter { it.begin > now && it.begin <= previousHorizon }
            .filter { !hasAlarm(it) }
            .distinctBy { it.key }
            .sortedBy { it.begin }
            .toList()
        return Result(announce, (kept + currentKeys).toSet())
    }

    /** O begin é o sufixo depois do último '_' da chave "eventId_begin". */
    fun keyBegin(key: String): Long? = key.substringAfterLast('_', "").toLongOrNull()
}
