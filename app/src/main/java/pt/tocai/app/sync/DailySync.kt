package pt.tocai.app.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import pt.tocai.app.data.SettingsStore
import java.util.Calendar

/**
 * Agendamento diário garantido: todos os dias, à hora escolhida (defeito 07:00), acorda a app com o
 * AlarmManager (não depende do WorkManager, que pode ser adiado) e corre AlarmScheduler.syncNow
 * através do [DailySyncReceiver].
 */
object DailySync {

    private const val TAG = "DailySync"
    const val ACTION_DAILY_SYNC = "pt.tocai.app.action.DAILY_SYNC"
    private const val REQUEST_CODE = 7007

    /** Agenda (ou reagenda) o próximo disparo para a próxima ocorrência da hora configurada. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val (hour, minute) = SettingsStore(app).dailySyncHourMinute
        val triggerAt = nextTriggerAt(System.currentTimeMillis(), hour, minute)
        val pi = pendingIntent(app)
        try {
            if (canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // a permissão de alarmes exatos pode ter sido retirada entretanto
            Log.w(TAG, "Sem alarmes exatos; a usar alarme inexato", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.w(TAG, "Falha a agendar o agendamento diário", e2)
            }
        }
    }

    /** Cancela o agendamento diário. */
    fun cancel(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(app)
        am.cancel(pi)
        pi.cancel()
    }

    /** Próxima ocorrência de hour:minute (hora local) estritamente depois de [now]. */
    fun nextTriggerAt(now: Long, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            // repõe a hora (mudança de hora de verão/inverno)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
        }
        return cal.timeInMillis
    }

    private fun canExact(am: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    private fun pendingIntent(app: Context): PendingIntent {
        val intent = Intent(app, DailySyncReceiver::class.java).setAction(ACTION_DAILY_SYNC)
        return PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
