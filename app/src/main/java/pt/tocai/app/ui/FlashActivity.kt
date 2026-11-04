package pt.tocai.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pt.tocai.app.R
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.sync.NotificationChannels
import pt.tocai.app.util.BluePulse
import pt.tocai.app.util.CallState

/**
 * "Luz de aviso": com o ecrã desligado, acende-o ~10 s com um fundo azul a piscar (faz de LED de
 * notificação), o ícone do Tocaí e o título/texto do aviso. Toque em qualquer sítio, ou 10 s, fecha;
 * "Abrir" desbloqueia e leva ao destino (escolha da manhã ou ecrã principal).
 *
 * Só é lançada pelo fullScreenIntent de uma notificação e só quando [shouldFlash] (ecrã desligado,
 * preferência blue_flash, sem chamada). Com o ecrã ligado mantém-se o comportamento normal.
 *
 * Escolha da manhã: o fullScreenIntent da notificação passa a ser esta atividade; ao fechar (toque ou
 * tempo) abre a MorningPickerActivity, que fica por trás do bloqueio e aparece ao desbloquear, como
 * antes. Marcação nova: é publicada uma notificação extra, sem som, no canal "Luz de aviso"
 * (IMPORTANCE_HIGH, necessário para o ecrã inteiro), que esta atividade apaga ao fechar.
 */
class FlashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FlashActivity"
        private const val EXTRA_TITLE = "pt.tocai.app.extra.FLASH_TITLE"
        private const val EXTRA_TEXT = "pt.tocai.app.extra.FLASH_TEXT"
        private const val EXTRA_DEST = "pt.tocai.app.extra.FLASH_DEST"
        private const val EXTRA_NOTIFICATION_ID = "pt.tocai.app.extra.FLASH_NOTIFICATION_ID"
        private const val SHOW_MS = 10_000L

        const val DEST_MAIN = 0
        const val DEST_MORNING = 1

        /** Notificação extra de luz para as marcações novas. */
        const val NEW_EVENT_FLASH_ID = 7310
        private const val REQ_MORNING = 7311
        private const val REQ_NEW_EVENT = 7312

        /** Deve acender o ecrã a piscar? Preferência ligada, ecrã desligado e sem chamada/reunião. */
        fun shouldFlash(ctx: Context): Boolean {
            val app = ctx.applicationContext ?: ctx
            if (!SettingsStore(app).blueFlash) return false
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            if (pm.isInteractive) return false
            return !CallState.isInCall(app)
        }

        fun intent(ctx: Context, title: String, text: String, dest: Int, notificationId: Int = 0): Intent =
            Intent(ctx, FlashActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_DEST, dest)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** fullScreenIntent da escolha da manhã quando o ecrã está desligado. */
        fun morningPendingIntent(ctx: Context, title: String, text: String): PendingIntent =
            PendingIntent.getActivity(
                ctx, REQ_MORNING, intent(ctx, title, text, DEST_MORNING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Marcações novas: se [shouldFlash], publica uma notificação silenciosa no canal "Luz de aviso"
         * com fullScreenIntent para esta atividade. Nunca lança exceções.
         */
        fun flashForNewEvents(ctx: Context, title: String, text: String) {
            try {
                val app = ctx.applicationContext ?: ctx
                if (!shouldFlash(app)) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) return
                NotificationChannels.create(app)
                val pi = PendingIntent.getActivity(
                    app, REQ_NEW_EVENT, intent(app, title, text, DEST_MAIN, NEW_EVENT_FLASH_ID),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(app, NotificationChannels.FLASH)
                    .setSmallIcon(R.drawable.ic_tocai_alarm)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(SHOW_MS + 20_000L)
                    .build()
                NotificationManagerCompat.from(app).notify(NEW_EVENT_FLASH_ID, n)
            } catch (e: Exception) {
                Log.w(TAG, "Falha a acender o ecrã a piscar", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pulse: ValueAnimator? = null
    private var done = false
    private var dest = DEST_MAIN
    private var notificationId = 0

    private lateinit var root: View

    private val timeoutRunnable = Runnable { close() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.flash_activity)
        root = findViewById(R.id.flash_root)
        root.setOnClickListener { close() }
        findViewById<Button>(R.id.flash_open).setOnClickListener { openDestination() }
        bind(intent)
        restartTimeout()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        done = false
        bind(intent)
        restartTimeout()
    }

    // Prazo absoluto desde a chegada (não depende de onStart/onStop, que oscilam ao acender o ecrã)
    private fun restartTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, SHOW_MS)
    }

    override fun onStart() {
        super.onStart()
        pulse?.cancel()
        pulse = BluePulse.start(root, window)
    }

    override fun onStop() {
        pulse?.cancel()
        pulse = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bind(i: Intent?) {
        dest = i?.getIntExtra(EXTRA_DEST, DEST_MAIN) ?: DEST_MAIN
        notificationId = i?.getIntExtra(EXTRA_NOTIFICATION_ID, 0) ?: 0
        findViewById<TextView>(R.id.flash_title).text =
            i?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.app_name) }
        val text = i?.getStringExtra(EXTRA_TEXT).orEmpty()
        findViewById<TextView>(R.id.flash_text).apply {
            this.text = text
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Fecha a luz. Na escolha da manhã, deixa a MorningPickerActivity à espera do desbloqueio. */
    private fun close() {
        if (done) return
        done = true
        if (dest == DEST_MORNING) startDestination()
        cancelFlashNotification()
        finishAndRemoveTask()
    }

    /** "Abrir": pede o desbloqueio e, se conseguir (ou se não houver bloqueio), abre o destino. */
    private fun openDestination() {
        if (done) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null || !km.isKeyguardLocked) {
            done = true
            startDestination()
            cancelFlashNotification()
            finishAndRemoveTask()
            return
        }
        handler.removeCallbacks(timeoutRunnable)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                if (done) return
                done = true
                startDestination()
                cancelFlashNotification()
                finishAndRemoveTask()
            }

            override fun onDismissCancelled() {
                close()
            }

            override fun onDismissError() {
                close()
            }
        })
    }

    private fun startDestination() {
        try {
            val i = if (dest == DEST_MORNING) {
                Intent(this, MorningPickerActivity::class.java)
                    .putExtra(MorningPickerActivity.EXTRA_FROM_NOTIFICATION, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a abrir o destino", e)
        }
    }

    private fun cancelFlashNotification() {
        if (notificationId == 0) return
        try {
            NotificationManagerCompat.from(this).cancel(notificationId)
        } catch (_: Exception) {
        }
    }
}
