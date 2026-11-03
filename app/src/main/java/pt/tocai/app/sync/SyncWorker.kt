package pt.tocai.app.sync

import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tocai.app.alarm.AlarmScheduler
import java.util.concurrent.TimeUnit

/** Volta a ler o calendário e acerta os despertadores. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            withContext(Dispatchers.IO) { AlarmScheduler.syncNow(ctx) }
        } catch (e: Exception) {
            // sem permissões ou falha do ContentResolver: tenta-se no próximo ciclo
            Log.w(TAG, "Falha na sincronização", e)
        } finally {
            // o vigia é de uso único: volta a pôr-se em fila para a próxima mudança
            if (tags.contains(TAG_WATCH)) {
                try {
                    enqueueWatch(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha a reativar o vigia do calendário", e)
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val TAG_WATCH = "calendar-watch"
        private const val WORK_WATCH = "calendar-watch"
        private const val WORK_PERIODIC = "periodic-sync"
        private const val WORK_NOW = "sync-now"

        /** Periódico de 30 min + vigia de mudanças no calendário. KEEP: nunca duplica. */
        fun ensureScheduled(ctx: Context) {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            wm.enqueueUniquePeriodicWork(
                WORK_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build(),
            )
            enqueueWatch(ctx, ExistingWorkPolicy.KEEP)
        }

        /** Sincroniza já (substitui um pedido pendente). */
        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                WORK_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
        }

        /** Trabalho que dispara quando o fornecedor de calendário muda (ex.: evento novo). */
        private fun enqueueWatch(ctx: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                WORK_WATCH,
                policy,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .addTag(TAG_WATCH)
                    .build(),
            )
        }
    }
}
