package pt.ricardo.caldespertador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Chamado pelo sistema à hora do despertador: arranca o som. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_RING
            putExtras(intent)
        }
        ContextCompat.startForegroundService(context, svc)
    }
}
