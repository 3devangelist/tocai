package pt.ricardo.caldespertador

import android.content.Context

/** Definições guardadas no telefone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var minutesBefore: Int
        get() = sp.getInt("minutesBefore", 5)
        set(v) = sp.edit().putInt("minutesBefore", v.coerceIn(0, 240)).apply()

    /** Calendários cujos eventos ganham despertador automaticamente. */
    var autoCalendars: Set<Long>
        get() = sp.getStringSet("autoCalendars", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("autoCalendars", v.map { it.toString() }.toSet()).apply()

    var firstRunDone: Boolean
        get() = sp.getBoolean("firstRunDone", false)
        set(v) = sp.edit().putBoolean("firstRunDone", v).apply()

    /** Eventos de calendários automáticos em que o utilizador desligou o despertador. */
    var disabledKeys: Set<String>
        get() = sp.getStringSet("disabledKeys", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("disabledKeys", v).apply()

    /** Eventos de outros calendários em que o utilizador ligou o despertador à mão. */
    var manualKeys: Set<String>
        get() = sp.getStringSet("manualKeys", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("manualKeys", v).apply()

    /** Despertadores atualmente agendados (chave do evento). */
    var scheduledKeys: Set<String>
        get() = sp.getStringSet("scheduledKeys", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("scheduledKeys", v).apply()

    fun isEnabled(ev: CalEvent): Boolean =
        if (ev.calendarId in autoCalendars) ev.key !in disabledKeys else ev.key in manualKeys

    fun setEnabled(ev: CalEvent, on: Boolean) {
        if (ev.calendarId in autoCalendars) {
            disabledKeys = if (on) disabledKeys - ev.key else disabledKeys + ev.key
        } else {
            manualKeys = if (on) manualKeys + ev.key else manualKeys - ev.key
        }
    }

    /** Remove chaves de eventos que já passaram, para a lista não crescer para sempre. */
    fun pruneOld(validKeys: Set<String>, nowMillis: Long) {
        fun keep(k: String): Boolean {
            val begin = k.substringAfterLast('_').toLongOrNull() ?: return false
            return begin > nowMillis - 24 * 3600_000L || k in validKeys
        }
        disabledKeys = disabledKeys.filter(::keep).toSet()
        manualKeys = manualKeys.filter(::keep).toSet()
    }
}
