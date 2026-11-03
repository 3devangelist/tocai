package pt.tocai.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import pt.tocai.app.R
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.PlannedAlarm
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.AlarmPlanner
import pt.tocai.app.ui.MainActivity

/** Lê o calendário, aplica as regras e agenda/cancela os despertadores no AlarmManager. */
object AlarmScheduler {

    private const val TAG = "TocaiAlarmScheduler"
    private const val ACTION_FIRE_PREFIX = "pt.tocai.app.FIRE/"
    private const val SNOOZE_PREFIX = "snooze:"
    private const val TEST_KEY = "test"
    private const val HOUR_MS = 60L * 60_000L
    private const val DAY_MS = 24L * HOUR_MS

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun syncNow(ctx: Context): List<PlannedAlarm> {
        val app = ctx.applicationContext ?: ctx
        if (!CalendarRepository.hasPermission(app) || !canScheduleExact(app)) {
            updateWidget(app)
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val store = SettingsStore(app)
        store.pruneEventKeys(now)

        val plan = computePlan(app, store, now)
        val planKeys = plan.map { it.alarmKey }.toSet()

        // Cancela os antigos que já não estão no plano (adiamentos e teste ficam intactos)
        for (key in store.scheduledAlarmKeys) {
            if (key in planKeys || isTransient(key)) continue
            cancel(app, key)
        }

        val scheduled = LinkedHashSet<String>()
        for (p in plan) {
            if (scheduleInternal(app, p.triggerAt, AlarmPayload.of(p))) scheduled.add(p.alarmKey)
        }
        store.scheduledAlarmKeys = scheduled

        updateWidget(app)
        return plan
    }

    fun currentPlan(ctx: Context): List<PlannedAlarm> {
        val app = ctx.applicationContext ?: ctx
        if (!CalendarRepository.hasPermission(app)) return emptyList()
        return computePlan(app, SettingsStore(app), System.currentTimeMillis())
    }

    fun nextAlarm(ctx: Context): PlannedAlarm? = currentPlan(ctx).firstOrNull()

    fun scheduleAt(ctx: Context, triggerAt: Long, payload: AlarmPayload) {
        scheduleInternal(ctx.applicationContext ?: ctx, triggerAt, payload)
    }

    fun cancel(ctx: Context, alarmKey: String) {
        val app = ctx.applicationContext ?: ctx
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            app,
            alarmKey.hashCode(),
            fireIntent(app, alarmKey),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        try {
            am.cancel(pi)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao cancelar $alarmKey", e)
        }
        pi.cancel()
    }

    fun scheduleSnooze(ctx: Context, payload: AlarmPayload, minutes: Int) {
        val key = if (payload.alarmKey.startsWith(SNOOZE_PREFIX)) payload.alarmKey
        else SNOOZE_PREFIX + payload.alarmKey
        val at = System.currentTimeMillis() + minutes.coerceIn(1, 1440) * 60_000L
        scheduleAt(ctx, at, payload.copy(alarmKey = key))
        updateWidget(ctx.applicationContext ?: ctx)
    }

    fun scheduleTest(ctx: Context, secondsFromNow: Int = 10) {
        val app = ctx.applicationContext ?: ctx
        val now = System.currentTimeMillis()
        val at = now + secondsFromNow.coerceAtLeast(1) * 1000L
        val payload = AlarmPayload(
            alarmKey = TEST_KEY,
            title = app.getString(R.string.alarm_test_title),
            location = app.getString(R.string.alarm_test_location),
            begin = at,
            offsetMinutes = 0,
        )
        scheduleAt(app, at, payload)
    }

    // --- interno ---

    private fun computePlan(ctx: Context, store: SettingsStore, now: Long): List<PlannedAlarm> {
        val days = store.lookAheadDays.coerceAtLeast(1)
        val events = try {
            CalendarRepository.events(ctx, now - HOUR_MS, now + days * DAY_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler o calendário", e)
            return emptyList()
        }
        return AlarmPlanner.plan(events, store.toRuleSettings(), now)
    }

    private fun isTransient(key: String): Boolean = key == TEST_KEY || key.startsWith(SNOOZE_PREFIX)

    /** Devolve true se o AlarmManager aceitou o despertador. */
    private fun scheduleInternal(ctx: Context, triggerAt: Long, payload: AlarmPayload): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        if (!canScheduleExact(ctx)) {
            Log.w(TAG, "Sem permissão para alarmes exatos; ${payload.alarmKey} não agendado")
            return false
        }
        val fire = PendingIntent.getBroadcast(
            ctx,
            payload.alarmKey.hashCode(),
            payload.writeTo(fireIntent(ctx, payload.alarmKey)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), fire)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão recusada ao agendar ${payload.alarmKey}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao agendar ${payload.alarmKey}", e)
            false
        }
    }

    /** Ação única por alarmKey para os PendingIntent nunca colidirem. */
    private fun fireIntent(ctx: Context, alarmKey: String): Intent =
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE_PREFIX + alarmKey)

    private fun updateWidget(ctx: Context) {
        try {
            pt.tocai.app.widget.NextAlarmWidget.updateAll(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao atualizar o widget", e)
        }
    }
}
