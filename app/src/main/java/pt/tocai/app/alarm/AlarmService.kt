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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import pt.tocai.app.R
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.SnoozePolicy
import pt.tocai.app.sync.NotificationChannels
import pt.tocai.app.sync.SyncWorker
import pt.tocai.app.util.CallState
import pt.tocai.app.util.PermissionsHelper
import pt.tocai.app.util.TimeFormat

/**
 * Serviço em primeiro plano que toca o despertador: som em loop, vibração e ecrã inteiro.
 * Em chamada/reunião (preferência call_discreet), passa ao modo discreto: notificação heads-up sem
 * ecrã inteiro, som de notificação 2 vezes e uma vibração curta, e depois sai do primeiro plano.
 */
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
        private const val CALL_CHECK_MS = 2_000L
        private const val SCREEN_WAKE_MS = 10_000L
        private const val DISCREET_PLAYS = 2
        private const val DISCREET_GAP_MS = 1_500L
        private const val DISCREET_MAX_MS = 12_000L
        private const val DISCREET_VIBRATION_MS = 300L

        /** Arranca o serviço a tocar (chamado pelo AlarmReceiver). */
        fun start(ctx: Context, payload: AlarmPayload) {
            val intent = payload.writeTo(Intent(ctx, AlarmService::class.java).setAction(ACTION_RING))
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /**
         * "Adiar" só aparece se o despertador foi marcado com pelo menos 10 min de antecedência e o
         * adiamento ainda toca antes do início do evento (ver [SnoozePolicy]). O de teste segue a mesma regra.
         */
        fun canSnooze(ctx: Context, payload: AlarmPayload?, now: Long = System.currentTimeMillis()): Boolean {
            if (payload == null) return false
            val minutes = try {
                SettingsStore(ctx).snoozeMinutes
            } catch (e: Exception) {
                return false
            }
            return SnoozePolicy.canSnooze(payload.offsetMinutes, now, payload.begin, minutes)
        }

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
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var discreet = false
    private var discreetPlays = 0
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

    // Enquanto toca normalmente: se começar uma chamada/reunião, baixa para o modo discreto
    private val callWatchRunnable = object : Runnable {
        override fun run() {
            val p = current ?: return
            if (!ringing || discreet) return
            if (CallState.isInCall(this@AlarmService)) {
                Log.i(TAG, "Chamada/reunião detetada a meio do toque: modo discreto")
                ringDiscreet(p, closeActivity = true)
            } else {
                handler.postDelayed(this, CALL_CHECK_MS)
            }
        }
    }

    // Fim do modo discreto: larga o primeiro plano mas deixa a notificação (Parar/Adiar)
    private val discreetDoneRunnable = Runnable { endDiscreet() }

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
                if (payload != null && !canSnooze(this, payload)) {
                    // ex.: ação "Adiar" de uma notificação antiga, quando já não há tempo antes do início
                    Log.i(TAG, "Adiar já não faz sentido para ${payload.alarmKey}; despertador parado")
                } else if (payload != null) {
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
        val settings = SettingsStore(this)
        if (settings.callDiscreet && CallState.isInCall(this)) {
            Log.i(TAG, "Em chamada/reunião: despertador em modo discreto")
            ringDiscreet(payload, closeActivity = false)
            return
        }

        // Se já estiver a tocar outro, este substitui-o
        stopOutputs()
        cancelDiscreetNotification()
        discreet = false
        current = payload
        ringing = true

        val maxRingMs = settings.maxRingMinutes.coerceAtLeast(1) * 60_000L

        startInForeground(payload)
        acquireWakeLock(maxRingMs + 10_000L)
        wakeScreenIfOff()
        abandonAudioFocus()
        requestAudioFocus()
        startSound(settings.ringtoneUri, settings.gradualVolume)
        if (settings.vibrate) startVibration()

        openAlarmScreen(payload)

        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, maxRingMs)
        if (settings.callDiscreet) {
            handler.removeCallbacks(callWatchRunnable)
            handler.postDelayed(callWatchRunnable, CALL_CHECK_MS)
        }
    }

    /**
     * Abre o ecrã do despertador. Com o telemóvel em uso, o Android mostra o fullScreenIntent só como
     * notificação heads-up e bloqueia aberturas de atividades a partir de segundo plano; com
     * "Mostrar por cima de outras apps" (SYSTEM_ALERT_WINDOW) a abertura é permitida.
     * Sem essa autorização tenta-se na mesma (resulta se o Tocaí estiver à frente); senão fica a
     * notificação em ecrã inteiro. Não é chamado no modo discreto (em chamada).
     */
    private fun openAlarmScreen(payload: AlarmPayload) {
        if (PermissionsHelper.startActivityFromBackground(this, activityIntent(payload))) return
        try {
            startActivity(activityIntent(payload))
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir a AlarmActivity diretamente", e)
        }
    }

    // --- modo discreto (em chamada/reunião) ---

    /**
     * Sem ecrã de alarme nem som em loop: notificação heads-up (canal "Despertadores em chamada",
     * sem fullScreenIntent) com Parar/Adiar, som de notificação 2 vezes e uma vibração curta.
     * A notificação é a do primeiro plano (startForegroundService exige startForeground) e fica
     * publicada quando o serviço sai do primeiro plano.
     */
    private fun ringDiscreet(payload: AlarmPayload, closeActivity: Boolean) {
        stopOutputs()
        abandonAudioFocus()
        releaseScreenWakeLock()
        current = payload
        ringing = true
        discreet = true
        discreetPlays = 0

        startInForeground(payload)   // com discreet = true usa a notificação discreta
        acquireWakeLock(DISCREET_MAX_MS + 5_000L)
        // Se o ecrã de alarme já estava aberto (a chamada começou a meio), fecha-o
        if (closeActivity) sendBroadcast(Intent(ACTION_ALARM_FINISHED).setPackage(packageName))

        if (SettingsStore(this).vibrate) vibrateShort()
        if (!startDiscreetSound()) {
            handler.post(discreetDoneRunnable)
        } else {
            handler.postDelayed(discreetDoneRunnable, DISCREET_MAX_MS)
        }
    }

    /** Som de notificação por defeito, [DISCREET_PLAYS] vezes com [DISCREET_GAP_MS] de intervalo. */
    private fun startDiscreetSound(): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return false
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = false
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Erro no som discreto ($what, $extra)")
                handler.post(discreetDoneRunnable)
                true
            }
            mp.setOnCompletionListener { done ->
                discreetPlays++
                if (discreetPlays < DISCREET_PLAYS) {
                    handler.postDelayed({
                        if (player === done) {
                            try {
                                done.seekTo(0)
                                done.start()
                            } catch (e: IllegalStateException) {
                                handler.post(discreetDoneRunnable)
                            }
                        }
                    }, DISCREET_GAP_MS)
                } else {
                    handler.post(discreetDoneRunnable)
                }
            }
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível tocar o som discreto", e)
            try {
                mp.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun vibrateShort() {
        try {
            val v = defaultVibrator() ?: return
            v.vibrate(VibrationEffect.createOneShot(DISCREET_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Falha a vibrar (discreto)", e)
        }
    }

    /** Fim dos toques discretos: liberta tudo e sai do primeiro plano, deixando a notificação. */
    private fun endDiscreet() {
        if (!discreet || !ringing) return
        handler.removeCallbacks(discreetDoneRunnable)
        stopOutputs()
        releaseWakeLock()
        ringing = false
        current = null
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a sair do primeiro plano", e)
        }
        stopSelf()
        try {
            SyncWorker.runNow(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao pedir sincronização", e)
        }
    }

    private fun cancelDiscreetNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    /** Reforço do setTurnScreenOn da AlarmActivity: com o ecrã desligado, acende-o uns segundos. */
    private fun wakeScreenIfOff() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (pm.isInteractive) return
            releaseScreenWakeLock()
            @Suppress("DEPRECATION")
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            screenWakeLock = pm.newWakeLock(flags, "tocai:screen").apply {
                setReferenceCounted(false)
                acquire(SCREEN_WAKE_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha a acender o ecrã", e)
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        screenWakeLock = null
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
        val showSnooze = canSnooze(this, payload)
        val title = payload.title.ifBlank { getString(R.string.ring_default_title) }
        val text = buildString {
            append(getString(R.string.ring_starts_at, TimeFormat.hm(payload.begin)))
            if (payload.location.isNotBlank()) append(" · ").append(payload.location)
        }

        if (discreet) {
            val at = TimeFormat.hm(if (payload.begin > 0L) payload.begin else System.currentTimeMillis())
            val builder = NotificationCompat.Builder(this, NotificationChannels.ALARM_CALL)
                .setSmallIcon(R.drawable.ic_tocai_alarm)
                .setContentTitle(getString(R.string.call_notification_title, at, title))
                .setContentText(getString(R.string.call_notification_text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setAutoCancel(true)
                // sem setSilent (impediria o heads-up): o canal já não tem som; os 2 toques são do serviço
                .setShowWhen(true)
                .setContentIntent(fullPi)
                .addAction(0, getString(R.string.ring_stop), stopPi)
            if (showSnooze) builder.addAction(0, getString(R.string.ring_snooze, snoozeMinutes), snoozePi)
            return builder.build()
        }

        val builder = NotificationCompat.Builder(this, NotificationChannels.ALARM)
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
            .setContentIntent(fullPi)   // tocar na notificação abre o ecrã do despertador
            .addAction(0, getString(R.string.ring_stop), stopPi)
        if (showSnooze) builder.addAction(0, getString(R.string.ring_snooze, snoozeMinutes), snoozePi)
        return builder.build()
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

    private fun defaultVibrator(): Vibrator? {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return if (v != null && v.hasVibrator()) v else null
    }

    private fun startVibration() {
        try {
            val v = defaultVibrator() ?: return
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
        handler.removeCallbacks(callWatchRunnable)
        handler.removeCallbacks(discreetDoneRunnable)
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
        discreet = false
        stopOutputs()
        abandonAudioFocus()
        releaseWakeLock()
        releaseScreenWakeLock()
        // Parar/Adiar a partir da notificação discreta (que pode já estar fora do primeiro plano)
        cancelDiscreetNotification()
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
        releaseScreenWakeLock()
        super.onDestroy()
    }
}
