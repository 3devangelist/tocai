package pt.tocai.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Chamado pelo AlarmManager à hora do despertador: arranca o serviço que toca. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload = AlarmPayload.from(intent)
        if (payload == null) {
            Log.w(TAG, "Intent sem despertador válido: ${intent.action}")
            return
        }
        try {
            AlarmService.start(context, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao arrancar o AlarmService", e)
        }
    }

    private companion object {
        const val TAG = "TocaiAlarmReceiver"
    }
}
