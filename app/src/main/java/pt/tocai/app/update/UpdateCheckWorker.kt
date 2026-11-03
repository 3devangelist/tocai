package pt.tocai.app.update

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pt.tocai.app.R
import pt.tocai.app.data.SettingsStore
import java.util.concurrent.TimeUnit

/** Uma vez por dia (com rede), vê se há versão nova e avisa uma única vez por versão. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SettingsStore(ctx)
        if (!store.autoUpdateCheck) return Result.success()
        val info = UpdateChecker.checkQuietly() ?: return Result.success()
        if (info.versionCode == store.lastNotifiedUpdate) return Result.success()
        if (notify(ctx, info)) store.lastNotifiedUpdate = info.versionCode
        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val WORK_NAME = "update-check"
        const val CHANNEL = "tocai_updates"
        private const val NOTIFICATION_ID = 7200

        /** Liga ou desliga a verificação diária conforme a preferência. */
        fun schedule(ctx: Context, enabled: Boolean = SettingsStore(ctx).autoUpdateCheck) {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build(),
            )
        }

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = ctx.getString(R.string.update_channel_desc) }
            nm.createNotificationChannel(ch)
        }

        private fun notify(ctx: Context, info: UpdateInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            createChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx,
                NOTIFICATION_ID,
                Intent(ctx, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tocai_alarm)
                .setContentTitle(ctx.getString(R.string.update_notification_title, info.versionName))
                .setContentText(ctx.getString(R.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            return try {
                NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Sem permissão para notificações", e)
                false
            }
        }
    }
}
