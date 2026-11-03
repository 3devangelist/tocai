package pt.tocai.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import pt.tocai.app.R

/** Canais de notificação da app (minSdk 26, por isso existem sempre). */
object NotificationChannels {
    const val ALARM = "tocai_alarm"
    const val STATUS = "tocai_status"

    fun create(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // Despertador: prioridade alta, sem som no canal (o AlarmService toca e vibra)
        val alarm = NotificationChannel(
            ALARM,
            ctx.getString(R.string.sync_channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.sync_channel_alarm_desc)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }

        // Estado: notificações discretas
        val status = NotificationChannel(
            STATUS,
            ctx.getString(R.string.sync_channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ctx.getString(R.string.sync_channel_status_desc)
            setShowBadge(false)
        }

        nm.createNotificationChannels(listOf(alarm, status))
    }
}
