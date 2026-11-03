package pt.tocai.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import pt.tocai.app.rules.RuleSettings

/** Acesso tipado às definições (SharedPreferences por defeito, partilhadas com o ecrã de Definições). */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // --- leitura segura (tolera valores com tipo errado) ---

    private fun str(key: String, def: String?): String? =
        try { sp.getString(key, def) } catch (e: ClassCastException) { def }

    private fun bool(key: String, def: Boolean): Boolean =
        try { sp.getBoolean(key, def) } catch (e: ClassCastException) { def }

    private fun strSet(key: String): Set<String> =
        try { sp.getStringSet(key, null)?.toSet() ?: emptySet() } catch (e: ClassCastException) { emptySet() }

    private fun intFromString(key: String, def: Int, range: IntRange): Int {
        val v = str(key, null)?.trim()?.toIntOrNull() ?: return def
        return if (v in range) v else def
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val e = sp.edit()
        e.block()
        e.apply()
    }

    // --- minutos antes ---

    var defaultOffsets: List<Int>
        get() = normalize(parseOffsets(str(SettingsKeys.DEFAULT_OFFSETS, DEFAULT_OFFSETS_CSV)))
            .ifEmpty { listOf(DEFAULT_OFFSET) }
        set(value) {
            val list = normalize(value).ifEmpty { listOf(DEFAULT_OFFSET) }
            edit { putString(SettingsKeys.DEFAULT_OFFSETS, list.joinToString(",")) }
        }

    var autoCalendarIds: Set<Long>
        get() = strSet(SettingsKeys.AUTO_CALENDAR_IDS).mapNotNull { it.trim().toLongOrNull() }.toSet()
        set(value) = edit { putStringSet(SettingsKeys.AUTO_CALENDAR_IDS, value.map { it.toString() }.toSet()) }

    /** Minutos próprios de um calendário; null = usa defaultOffsets. */
    fun calendarOffsets(calendarId: Long): List<Int>? = allCalendarOffsets()[calendarId]

    fun setCalendarOffsets(calendarId: Long, offsets: List<Int>?) {
        val map = allCalendarOffsets().toMutableMap()
        val list = offsets?.let { normalize(it) }
        if (list.isNullOrEmpty()) map.remove(calendarId) else map[calendarId] = list
        edit { putString(SettingsKeys.CALENDAR_OFFSETS, encodeCalendarOffsets(map)) }
    }

    /** Todos os minutos por calendário, formato "id:15,5;id2:10". */
    fun allCalendarOffsets(): Map<Long, List<Int>> {
        val raw = str(SettingsKeys.CALENDAR_OFFSETS, "") ?: ""
        val out = LinkedHashMap<Long, List<Int>>()
        for (part in raw.split(';')) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val id = part.substring(0, idx).trim().toLongOrNull() ?: continue
            val list = normalize(parseOffsets(part.substring(idx + 1)))
            if (list.isNotEmpty()) out[id] = list
        }
        return out
    }

    // --- filtros ---

    var includeKeywords: List<String>
        get() = parseKeywords(str(SettingsKeys.INCLUDE_KEYWORDS, ""))
        set(value) = edit { putString(SettingsKeys.INCLUDE_KEYWORDS, joinKeywords(value)) }

    var excludeKeywords: List<String>
        get() = parseKeywords(str(SettingsKeys.EXCLUDE_KEYWORDS, ""))
        set(value) = edit { putString(SettingsKeys.EXCLUDE_KEYWORDS, joinKeywords(value)) }

    var skipDeclined: Boolean
        get() = bool(SettingsKeys.SKIP_DECLINED, true)
        set(value) = edit { putBoolean(SettingsKeys.SKIP_DECLINED, value) }

    var lookAheadDays: Int
        get() = intFromString(SettingsKeys.LOOK_AHEAD_DAYS, 7, 1..60)
        set(value) = edit { putString(SettingsKeys.LOOK_AHEAD_DAYS, value.coerceIn(1, 60).toString()) }

    // --- som ---

    /** URI do toque; null = alarme do sistema. */
    var ringtoneUri: String?
        get() = str(SettingsKeys.RINGTONE_URI, null)?.takeIf { it.isNotBlank() }
        set(value) = edit {
            if (value.isNullOrBlank()) remove(SettingsKeys.RINGTONE_URI)
            else putString(SettingsKeys.RINGTONE_URI, value)
        }

    var vibrate: Boolean
        get() = bool(SettingsKeys.VIBRATE, true)
        set(value) = edit { putBoolean(SettingsKeys.VIBRATE, value) }

    var gradualVolume: Boolean
        get() = bool(SettingsKeys.GRADUAL_VOLUME, true)
        set(value) = edit { putBoolean(SettingsKeys.GRADUAL_VOLUME, value) }

    var snoozeMinutes: Int
        get() = intFromString(SettingsKeys.SNOOZE_MINUTES, 5, 1..120)
        set(value) = edit { putString(SettingsKeys.SNOOZE_MINUTES, value.coerceIn(1, 120).toString()) }

    var maxRingMinutes: Int
        get() = intFromString(SettingsKeys.MAX_RING_MINUTES, 3, 1..60)
        set(value) = edit { putString(SettingsKeys.MAX_RING_MINUTES, value.coerceIn(1, 60).toString()) }

    // --- estado ---

    var onboardingDone: Boolean
        get() = bool(SettingsKeys.ONBOARDING_DONE, false)
        set(value) = edit { putBoolean(SettingsKeys.ONBOARDING_DONE, value) }

    var forcedOn: Set<String>
        get() = strSet(SettingsKeys.FORCED_ON)
        set(value) = edit { putStringSet(SettingsKeys.FORCED_ON, value.toSet()) }

    var forcedOff: Set<String>
        get() = strSet(SettingsKeys.FORCED_OFF)
        set(value) = edit { putStringSet(SettingsKeys.FORCED_OFF, value.toSet()) }

    var scheduledAlarmKeys: Set<String>
        get() = strSet(SettingsKeys.SCHEDULED_ALARM_KEYS)
        set(value) = edit { putStringSet(SettingsKeys.SCHEDULED_ALARM_KEYS, value.toSet()) }

    // --- agendamento diário ---

    /** Hora do agendamento diário, "HH:mm" (24 h); inválida -> "07:00". */
    var dailySyncTime: String
        get() = parseTime(str(SettingsKeys.DAILY_SYNC_TIME, DEFAULT_DAILY_TIME))
            ?.let { formatTime(it.first, it.second) } ?: DEFAULT_DAILY_TIME
        set(value) {
            val t = parseTime(value)
            edit {
                putString(
                    SettingsKeys.DAILY_SYNC_TIME,
                    if (t == null) DEFAULT_DAILY_TIME else formatTime(t.first, t.second),
                )
            }
        }

    /** Hora e minuto do agendamento diário. */
    val dailySyncHourMinute: Pair<Int, Int>
        get() = parseTime(dailySyncTime) ?: (7 to 0)

    var dailySummary: Boolean
        get() = bool(SettingsKeys.DAILY_SUMMARY, true)
        set(value) = edit { putBoolean(SettingsKeys.DAILY_SUMMARY, value) }

    /** Liga/desliga à mão. autoEnabled = o que as regras dariam sem override. */
    fun setEventEnabled(event: CalendarEvent, on: Boolean, autoEnabled: Boolean) {
        val key = event.key
        val newOn = forcedOn.toMutableSet()
        val newOff = forcedOff.toMutableSet()
        newOn.remove(key)
        newOff.remove(key)
        // só guarda override quando difere das regras automáticas
        if (on && !autoEnabled) newOn.add(key)
        if (!on && autoEnabled) newOff.add(key)
        edit {
            putStringSet(SettingsKeys.FORCED_ON, newOn)
            putStringSet(SettingsKeys.FORCED_OFF, newOff)
        }
    }

    /** Remove chaves forcedOn/forcedOff de eventos com begin < now - 24h (o begin é o sufixo depois do último '_'). */
    fun pruneEventKeys(now: Long) {
        val limit = now - DAY_MS
        fun keep(k: String): Boolean {
            val begin = k.substringAfterLast('_', "").toLongOrNull() ?: return false
            return begin >= limit
        }
        val on = forcedOn
        val off = forcedOff
        val newOn = on.filter(::keep).toSet()
        val newOff = off.filter(::keep).toSet()
        if (newOn.size == on.size && newOff.size == off.size) return
        edit {
            putStringSet(SettingsKeys.FORCED_ON, newOn)
            putStringSet(SettingsKeys.FORCED_OFF, newOff)
        }
    }

    fun toRuleSettings(): RuleSettings = RuleSettings(
        defaultOffsets = defaultOffsets,
        autoCalendarIds = autoCalendarIds,
        calendarOffsets = allCalendarOffsets(),
        includeKeywords = includeKeywords,
        excludeKeywords = excludeKeywords,
        skipDeclined = skipDeclined,
        forcedOn = forcedOn,
        forcedOff = forcedOff,
    )

    companion object {
        private const val DEFAULT_OFFSET = 5
        private const val DEFAULT_OFFSETS_CSV = "5"
        private const val MAX_OFFSET = 1440
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_DAILY_TIME = "07:00"

        /** "7:05" / "07:05" -> (7, 5); inválido ou fora de 0..23 / 0..59 -> null. */
        fun parseTime(raw: String?): Pair<Int, Int>? {
            val parts = raw?.trim()?.split(':') ?: return null
            if (parts.size != 2) return null
            val h = parts[0].trim().toIntOrNull() ?: return null
            val m = parts[1].trim().toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h to m
        }

        /** (7, 5) -> "07:05". */
        fun formatTime(hour: Int, minute: Int): String =
            hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

        /** "15, 5,x" -> [15,5]; inválidos (não numéricos ou fora de 0..1440) ignorados; vazio -> []. */
        fun parseOffsets(csv: String?): List<Int> {
            if (csv.isNullOrBlank()) return emptyList()
            return csv.split(',', ';', ' ')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 0..MAX_OFFSET }
                .distinct()
        }

        /** Sem repetidos, dentro de 0..1440, por ordem decrescente. */
        private fun normalize(list: List<Int>): List<Int> =
            list.filter { it in 0..MAX_OFFSET }.distinct().sortedDescending()

        private fun encodeCalendarOffsets(map: Map<Long, List<Int>>): String =
            map.entries.joinToString(";") { (id, list) -> "$id:${list.joinToString(",")}" }

        private fun parseKeywords(csv: String?): List<String> {
            if (csv.isNullOrBlank()) return emptyList()
            return csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        private fun joinKeywords(list: List<String>): String =
            list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }
}
