package pt.ricardo.caldespertador

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Lê o calendário e agenda/cancela os despertadores. */
object AlarmScheduler {

    const val EXTRA_KEY = "key"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BEGIN = "begin"
    const val EXTRA_LOCATION = "location"

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun triggerTime(ctx: Context, ev: CalEvent) = ev.begin - Prefs(ctx).minutesBefore * 60_000L

    @Synchronized
    fun sync(ctx: Context) {
        if (!CalendarRepo.hasPermission(ctx) || !canScheduleExact(ctx)) return
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val events = CalendarRepo.upcoming(ctx, now)
        prefs.pruneOld(events.map { it.key }.toSet(), now)

        val wanted = events.filter { prefs.isEnabled(it) && triggerTime(ctx, it) > now }
        val wantedKeys = wanted.map { it.key }.toSet()

        // Cancelar os que deixaram de ser precisos (evento apagado, desligado, etc.)
        for (k in prefs.scheduledKeys - wantedKeys) cancel(ctx, k)

        for (ev in wanted) {
            schedule(ctx, ev.key, triggerTime(ctx, ev), ev.title, ev.begin, ev.location)
        }
        prefs.scheduledKeys = wantedKeys
    }

    fun schedule(ctx: Context, key: String, at: Long, title: String, begin: Long, location: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = firePendingIntent(ctx, key, title, begin, location)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
    }

    fun cancel(ctx: Context, key: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(firePendingIntent(ctx, key, "", 0, ""))
    }

    private fun firePendingIntent(ctx: Context, key: String, title: String, begin: Long, location: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "pt.ricardo.caldespertador.FIRE.$key" // ação única por evento
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BEGIN, begin)
            putExtra(EXTRA_LOCATION, location)
        }
        return PendingIntent.getBroadcast(
            ctx, key.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Próximo despertador agendado por esta app (para mostrar no ecrã). */
    fun nextScheduled(ctx: Context): Pair<Long, CalEvent>? {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        return CalendarRepo.upcoming(ctx, now)
            .filter { prefs.isEnabled(it) && triggerTime(ctx, it) > now }
            .minByOrNull { it.begin }
            ?.let { triggerTime(ctx, it) to it }
    }
}
