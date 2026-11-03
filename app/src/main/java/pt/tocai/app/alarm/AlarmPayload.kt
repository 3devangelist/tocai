package pt.tocai.app.alarm

import android.content.Intent
import pt.tocai.app.data.PlannedAlarm

/** Dados mínimos de um despertador, transportados nos extras do Intent. */
data class AlarmPayload(
    val alarmKey: String,
    val title: String,
    val location: String,
    val begin: Long,
    val offsetMinutes: Int,
) {
    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_ALARM_KEY, alarmKey)
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_LOCATION, location)
        putExtra(EXTRA_BEGIN, begin)
        putExtra(EXTRA_OFFSET, offsetMinutes)
    }

    companion object {
        private const val EXTRA_ALARM_KEY = "pt.tocai.app.extra.ALARM_KEY"
        private const val EXTRA_TITLE = "pt.tocai.app.extra.TITLE"
        private const val EXTRA_LOCATION = "pt.tocai.app.extra.LOCATION"
        private const val EXTRA_BEGIN = "pt.tocai.app.extra.BEGIN"
        private const val EXTRA_OFFSET = "pt.tocai.app.extra.OFFSET"

        /** null se o Intent não trouxer um despertador válido. */
        fun from(intent: Intent): AlarmPayload? {
            val key = try {
                intent.getStringExtra(EXTRA_ALARM_KEY)
            } catch (e: Exception) {
                null // extras corrompidos/não desserializáveis
            }
            if (key.isNullOrEmpty()) return null
            return AlarmPayload(
                alarmKey = key,
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                location = intent.getStringExtra(EXTRA_LOCATION).orEmpty(),
                begin = intent.getLongExtra(EXTRA_BEGIN, 0L),
                offsetMinutes = intent.getIntExtra(EXTRA_OFFSET, 0),
            )
        }

        fun of(p: PlannedAlarm): AlarmPayload = AlarmPayload(
            alarmKey = p.alarmKey,
            title = p.event.title,
            location = p.event.location,
            begin = p.event.begin,
            offsetMinutes = p.offsetMinutes,
        )
    }
}
