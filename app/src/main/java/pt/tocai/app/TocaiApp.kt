package pt.tocai.app

import android.app.Application
import android.util.Log
import pt.tocai.app.sync.DailySync
import pt.tocai.app.sync.NotificationChannels
import pt.tocai.app.sync.SyncWorker
import pt.tocai.app.update.UpdateCheckWorker
import pt.tocai.app.util.AppForeground

/** Ponto de entrada da app: cria os canais e garante a sincronização em segundo plano e o agendamento diário. */
class TocaiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            NotificationChannels.create(this)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a criar canais de notificação", e)
        }
        try {
            SyncWorker.ensureScheduled(this)
        } catch (e: Exception) {
            // ex.: arranque em modo direct boot, com o armazenamento ainda cifrado
            Log.w(TAG, "Falha a agendar a sincronização", e)
        }
        try {
            DailySync.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a agendar o agendamento diário", e)
        }
        AppForeground.register(this)
        try {
            UpdateCheckWorker.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a agendar a verificação de atualizações", e)
        }
    }

    private companion object {
        const val TAG = "TocaiApp"
    }
}
