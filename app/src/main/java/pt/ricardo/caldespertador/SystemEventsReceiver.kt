package pt.ricardo.caldespertador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reagenda tudo depois de reiniciar o telefone, mudar a hora, atualizar a app, etc. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncWorker.scheduleAll(context)
        SyncWorker.runNow(context)
    }
}
