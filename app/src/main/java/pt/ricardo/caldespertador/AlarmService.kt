package pt.ricardo.caldespertador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Toca o despertador (som de alarme + vibração) e mostra o ecrã de alarme. */
class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "pt.ricardo.caldespertador.RING"
        const val ACTION_STOP = "pt.ricardo.caldespertador.STOP"
        const val ACTION_SNOOZE = "pt.ricardo.caldespertador.SNOOZE"
        const val CHANNEL = "alarme"
        const val NOTIF_ID = 42
        const val SNOOZE_MIN = 5
        const val MAX_RING_MS = 3 * 60_000L

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Despertadores", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Despertadores criados a partir do calendário"
                    setSound(null, null) // o som é tocado pelo serviço, como alarme
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var current: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring(intent)
            ACTION_SNOOZE -> { snooze(); stopAll() }
            else -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun ring(intent: Intent) {
        current = intent
        createChannel(this)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Evento"
        val begin = intent.getLongExtra(AlarmScheduler.EXTRA_BEGIN, System.currentTimeMillis())
        val hora = SimpleDateFormat("HH:mm", Locale("pt", "PT")).format(Date(begin))

        val full = Intent(this, AlarmActivity::class.java).apply {
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val fullPi = PendingIntent.getActivity(this, 1, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 2,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozePi = PendingIntent.getService(this, 3,
            Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("⏰ $hora · $title")
            .setContentText(intent.getStringExtra(AlarmScheduler.EXTRA_LOCATION) ?: "")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(0, "Parar", stopPi)
            .addAction(0, "Adiar $SNOOZE_MIN min", snoozePi)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "caldespertador:ring")
            .apply { acquire(MAX_RING_MS + 10_000) }

        startSound()
        startVibration()
        try { startActivity(full) } catch (_: Exception) { /* fica a notificação em ecrã inteiro */ }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopAll() }, MAX_RING_MS)

        // Aproveita para reagendar os próximos
        SyncWorker.runNow(this)
    }

    private fun startSound() {
        stopSound()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { null }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vibrator = v
        val pattern = longArrayOf(0, 800, 600)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun snooze() {
        val i = current ?: return
        val key = i.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: return
        AlarmScheduler.schedule(
            this, "snooze_$key",
            System.currentTimeMillis() + SNOOZE_MIN * 60_000L,
            i.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "",
            i.getLongExtra(AlarmScheduler.EXTRA_BEGIN, 0),
            i.getStringExtra(AlarmScheduler.EXTRA_LOCATION) ?: "",
        )
    }

    private fun stopSound() {
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        player = null
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        vibrator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        sendBroadcast(Intent(AlarmActivity.ACTION_FINISH).setPackage(packageName))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        vibrator?.cancel()
        super.onDestroy()
    }
}
