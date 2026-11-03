package pt.tocai.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import pt.tocai.app.R

/** Leitura do calendário já sincronizado no telemóvel (CalendarContract), sem login. */
object CalendarRepository {

    private const val TAG = "CalendarRepository"
    private const val FALLBACK_COLOR = 0xFF1A73E8.toInt()

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Calendários visíveis no telemóvel, ordenados por nome. */
    fun calendars(ctx: Context): List<CalendarInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val unnamed = ctx.getString(R.string.data_unnamed_calendar)
        val out = mutableListOf<CalendarInfo>()
        try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue
                    out += CalendarInfo(
                        id = c.getLong(0),
                        name = c.str(1).ifBlank { unnamed },
                        account = c.str(2),
                        color = c.color(3),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para ler calendários", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Erro a ler calendários", e)
            return emptyList()
        }
        return out.sortedWith(
            compareBy<CalendarInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.account }
                .thenBy { it.id }
        )
    }

    /** Instâncias entre [from] e [to], ordenadas por begin; exclui canceladas; marca as recusadas. */
    fun events(ctx: Context, from: Long, to: Long): List<CalendarEvent> {
        if (!hasPermission(ctx) || to <= from) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        val uri = builder.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,              // 0
            CalendarContract.Instances.BEGIN,                 // 1
            CalendarContract.Instances.END,                   // 2
            CalendarContract.Instances.TITLE,                 // 3
            CalendarContract.Instances.EVENT_LOCATION,        // 4
            CalendarContract.Instances.DESCRIPTION,           // 5
            CalendarContract.Instances.CALENDAR_ID,           // 6
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, // 7
            CalendarContract.Instances.DISPLAY_COLOR,         // 8
            CalendarContract.Instances.ALL_DAY,               // 9
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,  // 10
            CalendarContract.Instances.STATUS,                // 11
        )
        val untitled = ctx.getString(R.string.data_untitled_event)
        val out = mutableListOf<CalendarEvent>()
        try {
            ctx.contentResolver.query(
                uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0) || c.isNull(1)) continue
                    if (!c.isNull(11) && c.getInt(11) == CalendarContract.Events.STATUS_CANCELED) continue
                    val begin = c.getLong(1)
                    val end = if (c.isNull(2)) begin else c.getLong(2)
                    out += CalendarEvent(
                        eventId = c.getLong(0),
                        begin = begin,
                        end = maxOf(end, begin),
                        title = c.str(3).trim().ifEmpty { untitled },
                        location = c.str(4).trim(),
                        description = c.str(5),
                        calendarId = if (c.isNull(6)) -1L else c.getLong(6),
                        calendarName = c.str(7),
                        color = c.color(8),
                        allDay = !c.isNull(9) && c.getInt(9) == 1,
                        declined = !c.isNull(10) &&
                            c.getInt(10) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para ler eventos", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Erro a ler eventos", e)
            return emptyList()
        }
        return out.sortedWith(compareBy<CalendarEvent> { it.begin }.thenBy { it.title }.thenBy { it.eventId })
    }

    private fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i) ?: ""

    /** Cor opaca; alguns fornecedores guardam a cor sem canal alfa. */
    private fun Cursor.color(i: Int): Int =
        if (isNull(i)) FALLBACK_COLOR else getInt(i) or 0xFF000000.toInt()
}
