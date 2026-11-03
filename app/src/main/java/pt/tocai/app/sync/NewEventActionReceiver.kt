package pt.tocai.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/** Ações da notificação de marcação nova: "Pôr despertador" e "Ignorar". Não exportado. */
class NewEventActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext ?: context
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        when (intent.action) {
            ACTION_IGNORE -> {
                if (id != 0) NotificationManagerCompat.from(app).cancel(id)
            }
            ACTION_ENABLE -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val begin = intent.getLongExtra(EXTRA_BEGIN, 0L)
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val grouped = intent.getBooleanExtra(EXTRA_GROUPED, false)
                val pending = goAsync()
                Thread {
                    try {
                        val alarm = try {
                            NewEventNotifier.enableAlarm(app, key, begin)
                        } catch (e: Exception) {
                            Log.w(TAG, "Falha a pôr o despertador", e)
                            null
                        }
                        if (id != 0) NewEventNotifier.showResult(app, id, title, alarm, grouped)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    companion object {
        private const val TAG = "NewEventActionReceiver"
        const val ACTION_ENABLE = "pt.tocai.app.action.NEW_EVENT_ENABLE"
        const val ACTION_IGNORE = "pt.tocai.app.action.NEW_EVENT_IGNORE"
        const val EXTRA_KEY = "pt.tocai.app.extra.EVENT_KEY"
        const val EXTRA_BEGIN = "pt.tocai.app.extra.EVENT_BEGIN"
        const val EXTRA_TITLE = "pt.tocai.app.extra.EVENT_TITLE"
        const val EXTRA_NOTIFICATION_ID = "pt.tocai.app.extra.NOTIFICATION_ID"
        const val EXTRA_GROUPED = "pt.tocai.app.extra.GROUPED"
    }
}
