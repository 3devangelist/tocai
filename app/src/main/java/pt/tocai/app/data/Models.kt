package pt.tocai.app.data

/** Um calendário sincronizado no telemóvel. */
data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

/** Uma instância de evento (repetições já expandidas). */
data class CalendarEvent(
    val eventId: Long, val begin: Long, val end: Long,
    val title: String, val location: String, val description: String,
    val calendarId: Long, val calendarName: String, val color: Int,
    val allDay: Boolean, val declined: Boolean,
) { val key: String get() = "${eventId}_$begin" }

/** Um despertador planeado. Um evento pode ter vários (ex.: 15 e 5 min antes). */
data class PlannedAlarm(val event: CalendarEvent, val triggerAt: Long, val offsetMinutes: Int) {
    val alarmKey: String get() = "${event.key}@$offsetMinutes"
}

object SettingsKeys {  // chaves das SharedPreferences por defeito (PreferenceManager.getDefaultSharedPreferences)
    const val DEFAULT_OFFSETS = "default_offsets"        // String CSV de minutos, ex. "15,5"; defeito "5"
    const val AUTO_CALENDAR_IDS = "auto_calendar_ids"    // StringSet de ids
    const val CALENDAR_OFFSETS = "calendar_offsets"      // String "id:15,5;id2:10" (vazio = usar defeito)
    const val INCLUDE_KEYWORDS = "include_keywords"      // String CSV; vazio = todos
    const val EXCLUDE_KEYWORDS = "exclude_keywords"      // String CSV
    const val SKIP_DECLINED = "skip_declined"            // Boolean, defeito true
    const val LOOK_AHEAD_DAYS = "look_ahead_days"        // String (ListPreference) "1","3","7","14"; defeito "7"
    const val RINGTONE_URI = "ringtone_uri"              // String; vazio/ausente = alarme do sistema
    const val VIBRATE = "vibrate"                        // Boolean, defeito true
    const val GRADUAL_VOLUME = "gradual_volume"          // Boolean, defeito true
    const val SNOOZE_MINUTES = "snooze_minutes"          // String "5","10","15"; defeito "5"
    const val MAX_RING_MINUTES = "max_ring_minutes"      // String "1","3","5","10"; defeito "3"
    const val CALL_DISCREET = "call_discreet"            // Boolean, defeito true: em chamada/reunião, só aviso discreto
    const val BLUE_FLASH = "blue_flash"                  // Boolean, defeito true: com o ecrã desligado, acende-o a piscar azul
    const val ONBOARDING_DONE = "onboarding_done"        // Boolean
    const val FORCED_ON = "forced_on"                    // StringSet de CalendarEvent.key
    const val FORCED_OFF = "forced_off"                  // StringSet de CalendarEvent.key
    const val SCHEDULED_ALARM_KEYS = "scheduled_alarm_keys" // StringSet de PlannedAlarm.alarmKey
    const val SEEN_EVENT_KEYS = "seen_event_keys"        // StringSet de CalendarEvent.key já vistos (avisos de marcações novas); ausente = ainda sem registo
    const val SEEN_HORIZON = "seen_horizon"              // Long: fim da janela da última verificação de marcações novas
    const val NOTIFY_NEW_EVENTS = "notify_new_events"    // Boolean, defeito true
    const val AUTO_UPDATE_CHECK = "auto_update_check"    // Boolean, defeito true
    const val LAST_NOTIFIED_UPDATE = "last_notified_update" // Int: versionCode da última atualização notificada
    const val DAILY_SYNC_TIME = "daily_sync_time"        // String "HH:mm"; defeito "07:00"
    const val DAILY_SUMMARY = "daily_summary"            // Boolean, defeito false (só com MORNING_PICKER desligado)
    const val MORNING_PICKER = "morning_picker"          // Boolean, defeito true: abre a escolha da manhã à hora DAILY_SYNC_TIME
    const val HIDDEN_CALENDAR_IDS = "hidden_calendar_ids" // StringSet de ids escondidos na UI; vazio = todos visíveis
    const val EVENT_OFFSETS = "event_offsets"            // String "eventKey:15,5;eventKey2:10" (minutos antes por evento)
}
