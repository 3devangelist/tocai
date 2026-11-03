package pt.ricardo.caldespertador

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Volta a ler o calendário e acerta os despertadores. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        AlarmScheduler.sync(applicationContext)
        if (tags.contains(TAG_WATCH)) watchCalendar(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val TAG_WATCH = "watch"

        fun scheduleAll(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork(
                "periodic-sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
            )
            watchCalendar(ctx)
        }

        /** Corre assim que o calendário do telefone mudar (ex.: nova consultoria marcada). */
        fun watchCalendar(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "calendar-watch", policy,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .addTag(TAG_WATCH)
                    .build()
            )
        }

        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
        }
    }
}
