package pt.tocai.app.rules

import pt.tocai.app.data.PlannedAlarm

/** Decisões puras do AlarmScheduler.syncNow (sem Android, testáveis na JVM). */
object SyncDecision {

    /**
     * Chaves agendadas a cancelar. [plan] null = a leitura do calendário falhou: não se cancela nada,
     * para uma falha momentânea não apagar despertadores válidos. Um plano vazio (calendário vazio
     * de verdade) cancela tudo o que não for transitório ([isTransient]: adiamentos e teste).
     */
    fun keysToCancel(
        scheduled: Set<String>,
        plan: List<PlannedAlarm>?,
        isTransient: (String) -> Boolean,
    ): Set<String> {
        if (plan == null) return emptySet()
        val planKeys = plan.map { it.alarmKey }.toSet()
        return scheduled.filterTo(LinkedHashSet()) { it !in planKeys && !isTransient(it) }
    }
}
