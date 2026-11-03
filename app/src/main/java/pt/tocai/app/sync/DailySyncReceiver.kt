package pt.tocai.app.sync

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.PlannedAlarm
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.ui.MainActivity
import pt.tocai.app.util.TimeFormat
import java.util.Calendar

/** Disparo diário: sincroniza, reagenda o dia seguinte e (opcionalmente) mostra o resumo do dia. */
class DailySyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext ?: context
        val pending = goAsync()
        Thread {
            try {
                run(app)
            } catch (e: Exception) {
                Log.w(TAG, "Falha no agendamento diário", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun run(app: Context) {
        val plan = try {
            AlarmScheduler.syncNow(app).ifEmpty { AlarmScheduler.currentPlan(app) }
        } catch (e: Exception) {
            Log.w(TAG, "Falha na sincronização diária", e)
            emptyList()
        } finally {
            // reagenda sempre o dia seguinte, aconteça o que acontecer
            try {
                DailySync.schedule(app)
            } catch (e: Exception) {
                Log.w(TAG, "Falha a reagendar o agendamento diário", e)
            }
        }
        try {
            if (SettingsStore(app).dailySummary) postSummary(app, plan)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a publicar o resumo do dia", e)
        }
    }

    private fun postSummary(app: Context, plan: List<PlannedAlarm>) {
        if (!canNotify(app)) return
        val now = System.currentTimeMillis()
        val endOfDay = endOfDay(now)
        val today = plan.filter { it.triggerAt in now..endOfDay }.sortedBy { it.triggerAt }

        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(app, NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_tocai_alarm)
            .setContentTitle(app.getString(R.string.daily_summary_title))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (today.isEmpty()) {
            builder.setContentText(app.getString(R.string.daily_summary_none))
        } else {
            val lines = today.map { app.getString(R.string.daily_summary_line, TimeFormat.hm(it.triggerAt), it.event.title) }
            val style = NotificationCompat.InboxStyle()
            lines.take(MAX_LINES).forEach { style.addLine(it) }
            if (lines.size > MAX_LINES) {
                style.setSummaryText(app.getString(R.string.daily_summary_more, lines.size - MAX_LINES))
            }
            builder.setContentText(lines.joinToString(" · "))
                .setNumber(today.size)
                .setStyle(style)
        }

        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificações", e)
        }
    }

    private fun canNotify(app: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun endOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private companion object {
        const val TAG = "DailySyncReceiver"
        const val NOTIFICATION_ID = 7008
        const val MAX_LINES = 6
    }
}
