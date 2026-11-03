package pt.tocai.app.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import pt.tocai.app.R
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.sync.NotificationChannels
import pt.tocai.app.sync.SyncWorker
import pt.tocai.app.util.TimeFormat

/** Serviço em primeiro plano que toca o despertador: som em loop, vibração e ecrã inteiro. */
class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "pt.tocai.app.action.RING"
        const val ACTION_STOP = "pt.tocai.app.action.STOP"
        const val ACTION_SNOOZE = "pt.tocai.app.action.SNOOZE"
        const val ACTION_ALARM_FINISHED = "pt.tocai.app.action.ALARM_FINISHED"

        private const val TAG = "TocaiAlarmService"
        private const val NOTIF_ID = 4201
        private const val RAMP_MS = 30_000L
        private const val RAMP_STEP_MS = 1_000L
        private const val RAMP_START_VOLUME = 0.1f
        private const val REQ_FULL_SCREEN = 1
        private const val REQ_STOP = 2
        private const val REQ_SNOOZE = 3

        /** Arranca o serviço a tocar (chamado pelo AlarmReceiver). */
        fun start(ctx: Context, payload: AlarmPayload) {
            val intent = payload.writeTo(Intent(ctx, AlarmService::class.java).setAction(ACTION_RING))
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        fun snooze(ctx: Context) = send(ctx, ACTION_SNOOZE)

        private fun send(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
            } catch (e: Exception) {
                // Serviço já parado e app em segundo plano: basta avisar a atividade para fechar
                Log.w(TAG, "Não foi possível enviar $action ao serviço", e)
                ctx.sendBroadcast(Intent(ACTION_ALARM_FINISHED).setPackage(ctx.packageName))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var current: AlarmPayload? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var rampStart = 0L
    private var ringing = false

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Sobe o volume de 0.1 a 1.0 ao longo de 30 s
    private val rampRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val elapsed = SystemClock.elapsedRealtime() - rampStart
            val v = (RAMP_START_VOLUME + (1f - RAMP_START_VOLUME) * elapsed / RAMP_MS.toFloat())
                .coerceIn(RAMP_START_VOLUME, 1f)
            try {
                p.setVolume(v, v)
            } catch (e: IllegalStateException) {
                return
            }
            if (v < 1f) handler.postDelayed(this, RAMP_STEP_MS)
        }
    }

    // Para sozinho ao fim de maxRingMinutes
    private val timeoutRunnable = Runnable { finishRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            finishRinging()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_RING -> {
                val payload = AlarmPayload.from(intent)
                if (payload != null) {
                    ring(payload)
                } else {
                    // startForegroundService exige startForeground, mesmo sem dados válidos
                    Log.w(TAG, "ACTION_RING sem despertador válido")
                    if (!ringing) {
                        startInForeground(fallbackPayload())
                        finishRinging()
                    }
                }
            }
            ACTION_SNOOZE -> {
                val payload = AlarmPayload.from(intent) ?: current
                if (payload != null) {
                    try {
                        AlarmScheduler.scheduleSnooze(this, payload, SettingsStore(this).snoozeMinutes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Falha ao adiar o despertador", e)
                    }
                }
                finishRinging()
            }
            else -> finishRinging()
        }
        return START_NOT_STICKY
    }

    private fun ring(payload: AlarmPayload) {
        // Se já estiver a tocar outro, este substitui-o
        stopOutputs()
        current = payload
        ringing = true

        val settings = SettingsStore(this)
        val maxRingMs = settings.maxRingMinutes.coerceAtLeast(1) * 60_000L

        startInForeground(payload)
        acquireWakeLock(maxRingMs + 10_000L)
        abandonAudioFocus()
        requestAudioFocus()
        startSound(settings.ringtoneUri, settings.gradualVolume)
        if (settings.vibrate) startVibration()

        // Em versões recentes o sistema pode bloquear isto; fica a notificação em ecrã inteiro
        try {
            startActivity(activityIntent(payload))
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir a AlarmActivity diretamente", e)
        }

        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, maxRingMs)
    }

    private fun fallbackPayload() = AlarmPayload(
        alarmKey = "desconhecido",
        title = getString(R.string.ring_default_title),
        location = "",
        begin = System.currentTimeMillis(),
        offsetMinutes = 0,
    )

    private fun activityIntent(payload: AlarmPayload): Intent =
        payload.writeTo(Intent(this, AlarmActivity::class.java)).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        )

    private fun buildNotification(payload: AlarmPayload): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullPi = PendingIntent.getActivity(this, REQ_FULL_SCREEN, activityIntent(payload), piFlags)
        val stopPi = PendingIntent.getService(
            this, REQ_STOP,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP), piFlags
        )
        val snoozePi = PendingIntent.getService(
            this, REQ_SNOOZE,
            payload.writeTo(Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE)), piFlags
        )
        val snoozeMinutes = SettingsStore(this).snoozeMinutes
        val title = payload.title.ifBlank { getString(R.string.ring_default_title) }
        val text = buildString {
            append(getString(R.string.ring_starts_at, TimeFormat.hm(payload.begin)))
            if (payload.location.isNotBlank()) append(" · ").append(payload.location)
        }

        return NotificationCompat.Builder(this, NotificationChannels.ALARM)
            .setSmallIcon(R.drawable.ic_tocai_alarm)
            .setContentTitle(getString(R.string.ring_notification_title, title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(0, getString(R.string.ring_stop), stopPi)
            .addAction(0, getString(R.string.ring_snooze, snoozeMinutes), snoozePi)
            .build()
    }

    private fun startInForeground(payload: AlarmPayload) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(payload), type)
        } catch (e: Exception) {
            // Ex.: ForegroundServiceStartNotAllowedException; continua a tentar tocar
            Log.e(TAG, "Falha no startForeground", e)
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tocai:ring").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao adquirir o wakelock", e)
        }
    }

    private fun requestAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttributes)
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao pedir o foco de áudio", e)
        }
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        try {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(req)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao largar o foco de áudio", e)
        }
    }

    /** URIs a tentar, por ordem: escolhido pelo utilizador, alarme do sistema, toque, notificação. */
    private fun candidateUris(custom: String?): List<Uri> {
        val list = ArrayList<Uri>()
        if (!custom.isNullOrBlank()) {
            try {
                list.add(Uri.parse(custom))
            } catch (_: Exception) {
            }
        }
        try {
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)?.let { list.add(it) }
        } catch (_: Exception) {
        }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { list.add(it) }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { list.add(it) }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { list.add(it) }
        return list.distinct()
    }

    private fun startSound(customUri: String?, gradual: Boolean) {
        val startVolume = if (gradual) RAMP_START_VOLUME else 1f
        for (uri in candidateUris(customUri)) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(alarmAttributes)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setVolume(startVolume, startVolume)
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Erro no MediaPlayer ($what, $extra)")
                    true
                }
                mp.prepare()
                mp.start()
                player = mp
                break
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível tocar $uri", e)
                try {
                    mp.release()
                } catch (_: Exception) {
                }
            }
        }
        if (player == null) {
            Log.e(TAG, "Nenhum som disponível; fica só a vibração e o ecrã")
            return
        }
        if (gradual) {
            rampStart = SystemClock.elapsedRealtime()
            handler.removeCallbacks(rampRunnable)
            handler.postDelayed(rampRunnable, RAMP_STEP_MS)
        }
    }

    private fun startVibration() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            vibrator = v
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, alarmAttributes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao vibrar", e)
        }
    }

    /** Pára som, vibração e temporizadores, sem largar o primeiro plano. */
    private fun stopOutputs() {
        handler.removeCallbacks(rampRunnable)
        handler.removeCallbacks(timeoutRunnable)
        player?.let { mp ->
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    /** Termina o toque: limpa tudo, fecha a atividade, sai do primeiro plano e reagenda. */
    private fun finishRinging() {
        val wasRinging = ringing
        ringing = false
        current = null
        stopOutputs()
        abandonAudioFocus()
        releaseWakeLock()
        sendBroadcast(Intent(ACTION_ALARM_FINISHED).setPackage(packageName))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (wasRinging) {
            try {
                SyncWorker.runNow(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao pedir sincronização", e)
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopOutputs()
        abandonAudioFocus()
        releaseWakeLock()
        super.onDestroy()
    }
}
