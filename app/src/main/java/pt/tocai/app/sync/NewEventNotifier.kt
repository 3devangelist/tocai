package pt.tocai.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.PlannedAlarm
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.AlarmPlanner
import pt.tocai.app.ui.MainActivity
import pt.tocai.app.ui.NewEventPrompt
import pt.tocai.app.util.TimeFormat

/**
 * Aviso de marcação nova: quando o calendário muda, deteta eventos novos sem despertador e
 * propõe pôr-lhes despertador (diálogo se a app estiver aberta, notificação se não estiver).
 */
object NewEventNotifier {

    private const val TAG = "NewEventNotifier"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val CHANNEL = "tocai_new_events"
    private const val GROUP = "pt.tocai.app.NEW_EVENTS"
    private const val SUMMARY_ID = 7300
    private const val GROUP_THRESHOLD = 3
    private const val MAX_NOTIFICATIONS = 20

    /** Chamado pelo SyncWorker depois de sincronizar. Nunca lança exceções. */
    @Synchronized
    fun check(ctx: Context) {
        try {
            val app = ctx.applicationContext ?: ctx
            if (!CalendarRepository.hasPermission(app)) return
            val store = SettingsStore(app)
            val now = System.currentTimeMillis()
            val horizon = now + store.lookAheadDays.coerceAtLeast(1) * DAY_MS
            val events = CalendarRepository.events(app, now, horizon)
            val rs = store.toRuleSettings()
            val result = NewEventDetector.detect(events, store.seenEventKeys, store.seenHorizon, now) {
                AlarmPlanner.isEnabled(it, rs)
            }
            store.seenEventKeys = result.seen
            store.seenHorizon = horizon
            if (!store.notifyNewEvents || result.toAnnounce.isEmpty()) return
            // app aberta: mostra dentro da app; senão, notificação
            if (NewEventPrompt.tryDeliver(app, result.toAnnounce)) return
            postNotifications(app, result.toAnnounce)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a procurar marcações novas", e)
        }
    }

    /** "Nova marcação: Dentista — amanhã às 14:30". */
    fun label(ctx: Context, e: CalendarEvent): String =
        ctx.getString(R.string.newevent_label, displayTitle(ctx, e), TimeFormat.relativeDay(e.begin), TimeFormat.hm(e.begin))

    fun displayTitle(ctx: Context, e: CalendarEvent): String =
        e.title.ifBlank { ctx.getString(R.string.newevent_untitled) }

    /**
     * Liga o despertador de um evento (pela chave "eventId_begin") e sincroniza.
     * Devolve o primeiro despertador do evento, ou null se não foi possível. Corre fora da thread principal.
     */
    fun enableAlarm(ctx: Context, key: String, begin: Long): PlannedAlarm? {
        val app = ctx.applicationContext ?: ctx
        val store = SettingsStore(app)
        val event = CalendarRepository.events(app, begin - 60_000L, begin + 60_000L)
            .firstOrNull { it.key == key } ?: return null
        val rs = store.toRuleSettings()
        store.setEventEnabled(event, true, AlarmPlanner.isAutoEnabled(event, rs))
        val plan = AlarmScheduler.syncNow(app)
        return plan.firstOrNull { it.event.key == key }
    }

    // --- notificações ---

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL,
            ctx.getString(R.string.newevent_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = ctx.getString(R.string.newevent_channel_desc) }
        nm.createNotificationChannel(ch)
    }

    fun notificationId(key: String): Int = 0x10000000 or (key.hashCode() and 0x0FFFFFFF)

    fun postNotifications(ctx: Context, events: List<CalendarEvent>) {
        val app = ctx.applicationContext ?: ctx
        if (!canNotify(app)) return
        createChannel(app)
        val grouped = events.size > GROUP_THRESHOLD
        val nm = NotificationManagerCompat.from(app)
        try {
            for (e in events.take(MAX_NOTIFICATIONS)) {
                nm.notify(notificationId(e.key), buildEventNotification(app, e, grouped))
            }
            if (grouped) nm.notify(SUMMARY_ID, buildSummary(app, events))
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificações", e)
        }
    }

    private fun buildEventNotification(app: Context, e: CalendarEvent, grouped: Boolean) =
        NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_tocai_alarm)
            .setContentTitle(app.getString(R.string.newevent_title, displayTitle(app, e)))
            .setContentText(app.getString(R.string.newevent_when, TimeFormat.relativeDay(e.begin), TimeFormat.hm(e.begin)))
            .setContentIntent(openApp(app))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, app.getString(R.string.newevent_action_enable), action(app, NewEventActionReceiver.ACTION_ENABLE, e, grouped))
            .addAction(0, app.getString(R.string.newevent_action_ignore), action(app, NewEventActionReceiver.ACTION_IGNORE, e, grouped))
            .apply { if (grouped) setGroup(GROUP) }
            .build()

    private fun buildSummary(app: Context, events: List<CalendarEvent>) =
        NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_tocai_alarm)
            .setContentTitle(app.getString(R.string.newevent_summary_title, events.size))
            .setContentText(events.joinToString(" · ") { displayTitle(app, it) })
            .setStyle(
                NotificationCompat.InboxStyle().also { s ->
                    events.take(6).forEach { s.addLine(label(app, it)) }
                }
            )
            .setContentIntent(openApp(app))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setNumber(events.size)
            .build()

    /** Depois de "Pôr despertador": substitui a notificação pelo resultado. */
    fun showResult(ctx: Context, notificationId: Int, title: String, alarm: PlannedAlarm?, grouped: Boolean) {
        val app = ctx.applicationContext ?: ctx
        if (!canNotify(app)) return
        val text = if (alarm != null) {
            app.getString(R.string.newevent_enabled, TimeFormat.hm(alarm.triggerAt))
        } else {
            app.getString(R.string.newevent_enable_failed)
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_tocai_alarm)
            .setContentTitle(app.getString(R.string.newevent_title, title))
            .setContentText(text)
            .setContentIntent(openApp(app))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (grouped) setGroup(GROUP) }
            .build()
        try {
            NotificationManagerCompat.from(app).notify(notificationId, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificações", e)
        }
    }

    private fun action(app: Context, action: String, e: CalendarEvent, grouped: Boolean): PendingIntent {
        val id = notificationId(e.key)
        val intent = Intent(app, NewEventActionReceiver::class.java)
            .setAction(action)
            .setPackage(app.packageName)
            .putExtra(NewEventActionReceiver.EXTRA_KEY, e.key)
            .putExtra(NewEventActionReceiver.EXTRA_BEGIN, e.begin)
            .putExtra(NewEventActionReceiver.EXTRA_TITLE, displayTitle(app, e))
            .putExtra(NewEventActionReceiver.EXTRA_NOTIFICATION_ID, id)
            .putExtra(NewEventActionReceiver.EXTRA_GROUPED, grouped)
        val request = id xor action.hashCode()
        return PendingIntent.getBroadcast(
            app, request, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(app: Context): PendingIntent = PendingIntent.getActivity(
        app,
        SUMMARY_ID,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canNotify(app: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }
}
