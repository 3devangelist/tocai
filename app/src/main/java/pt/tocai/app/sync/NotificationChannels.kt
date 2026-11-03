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
    const val MORNING = "tocai_morning"

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

        // Escolha da manhã: prioridade alta (para o ecrã inteiro/heads-up), mas sem som nem vibração
        val morning = NotificationChannel(
            MORNING,
            ctx.getString(R.string.sync_channel_morning_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.sync_channel_morning_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }

        nm.createNotificationChannels(listOf(alarm, status, morning))
    }
}
