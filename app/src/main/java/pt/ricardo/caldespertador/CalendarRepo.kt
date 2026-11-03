package pt.ricardo.caldespertador

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

data class CalInfo(val id: Long, val name: String, val account: String, val color: Int)

data class CalEvent(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val location: String,
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
) {
    val key: String get() = "${eventId}_$begin"
}

object CalendarRepo {

    const val DAYS_AHEAD = 7

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, null, null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalInfo(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getInt(3))
            }
        }
        return out
    }

    /** Eventos (instâncias, já com repetições expandidas) entre agora e daqui a [DAYS_AHEAD] dias. */
    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val from = now - 3600_000L
        val to = now + DAYS_AHEAD * 24 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS,
        )
        val out = mutableListOf<CalEvent>()
        ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(8) == 1) continue // dia inteiro: sem despertador
                if (c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (c.getInt(10) == CalendarContract.Events.STATUS_CANCELED) continue
                val begin = c.getLong(1)
                if (begin < now - 3600_000L) continue
                out += CalEvent(
                    eventId = c.getLong(0),
                    begin = begin,
                    end = c.getLong(2),
                    title = c.getString(3)?.takeIf { it.isNotBlank() } ?: "(sem título)",
                    location = c.getString(4) ?: "",
                    calendarId = c.getLong(5),
                    calendarName = c.getString(6) ?: "",
                    color = c.getInt(7),
                )
            }
        }
        return out
    }
}
