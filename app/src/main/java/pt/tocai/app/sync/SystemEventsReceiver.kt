package pt.tocai.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

/** Reagenda tudo depois de reiniciar, mudar a hora/fuso, atualizar a app ou mudar a permissão de alarmes exatos. */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Em direct boot o armazenamento da app ainda está cifrado; o BOOT_COMPLETED trata disso depois
        val um = context.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) {
            Log.i(TAG, "Utilizador bloqueado (${intent.action}); aguarda BOOT_COMPLETED")
            return
        }
        try {
            SyncWorker.ensureScheduled(context)
            SyncWorker.runNow(context)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a reagendar após ${intent.action}", e)
        }
    }

    private companion object {
        const val TAG = "SystemEventsReceiver"
    }
}
