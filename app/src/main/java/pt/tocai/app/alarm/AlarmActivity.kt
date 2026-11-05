package pt.tocai.app.alarm

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import pt.tocai.app.R
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.util.BluePulse
import pt.tocai.app.util.TimeFormat

/**
 * Ecrã do despertador: aparece por cima do ecrã de bloqueio, com Parar e Adiar.
 * "Adiar" só aparece quando faz sentido (AlarmService.canSnooze); senão fica só "Parar", a toda a largura.
 * Com a preferência blue_flash, o fundo pulsa entre azul escuro e azul vivo (faz de LED).
 */
class AlarmActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var pulse: ValueAnimator? = null

    private lateinit var root: View

    private lateinit var txtNow: TextView
    private lateinit var txtTitle: TextView
    private lateinit var txtStarts: TextView
    private lateinit var txtLocation: TextView
    private lateinit var btnStop: Button
    private lateinit var btnSnooze: Button
    private var payload: AlarmPayload? = null

    // Fecha quando o serviço termina (parado, adiado ou tempo esgotado)
    private val finishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finishAndRemoveTask()
        }
    }

    // Atualiza a hora no início de cada minuto
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            txtNow.text = TimeFormat.hm(now)
            updateSnooze(now)
            handler.postDelayed(this, 60_000L - now % 60_000L + 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.ring_activity_alarm)

        root = findViewById(R.id.ring_root)
        txtNow = findViewById(R.id.ring_txt_now)
        txtTitle = findViewById(R.id.ring_txt_title)
        txtStarts = findViewById(R.id.ring_txt_starts)
        txtLocation = findViewById(R.id.ring_txt_location)
        btnStop = findViewById(R.id.ring_btn_stop)
        btnSnooze = findViewById(R.id.ring_btn_snooze)

        btnStop.setOnClickListener {
            AlarmService.stop(this)
            finishAndRemoveTask()
        }
        btnSnooze.setOnClickListener {
            if (!AlarmService.canSnooze(this, payload)) {
                // entretanto deixou de haver tempo antes do início
                updateSnooze(System.currentTimeMillis())
                return@setOnClickListener
            }
            AlarmService.snooze(this)
            val minutes = SettingsStore(this).snoozeMinutes
            Toast.makeText(this, getString(R.string.ring_snoozed_toast, minutes), Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }

        // O botão voltar não fecha: é preciso escolher Parar ou Adiar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val msg = if (btnSnooze.visibility == View.VISIBLE) R.string.ring_back_blocked
                else R.string.ring_back_blocked_stop_only
                Toast.makeText(this@AlarmActivity, msg, Toast.LENGTH_SHORT).show()
            }
        })

        ContextCompat.registerReceiver(
            this,
            finishedReceiver,
            IntentFilter(AlarmService.ACTION_ALARM_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(clockRunnable)
        clockRunnable.run()
        pulse?.cancel()
        pulse = if (SettingsStore(this).blueFlash) BluePulse.start(root, window) else null
    }

    override fun onStop() {
        handler.removeCallbacks(clockRunnable)
        pulse?.cancel()
        pulse = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try {
                unregisterReceiver(finishedReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
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

    private fun bind(i: Intent?) {
        val payload = i?.let { AlarmPayload.from(it) }
        this.payload = payload
        txtNow.text = TimeFormat.hm(System.currentTimeMillis())

        val title = payload?.title.orEmpty().ifBlank { getString(R.string.ring_default_title) }
        txtTitle.text = title

        if (payload != null && payload.begin > 0L) {
            txtStarts.text = getString(R.string.ring_starts_at, TimeFormat.hm(payload.begin))
            txtStarts.visibility = View.VISIBLE
        } else {
            txtStarts.visibility = View.GONE
        }

        val location = payload?.location.orEmpty()
        txtLocation.text = location
        txtLocation.visibility = if (location.isBlank()) View.GONE else View.VISIBLE

        btnSnooze.text = getString(R.string.ring_snooze, SettingsStore(this).snoozeMinutes)
        updateSnooze(System.currentTimeMillis())
    }

    /** Mostra "Adiar" só se ainda fizer sentido; o "Parar" já ocupa a largura toda. */
    private fun updateSnooze(now: Long) {
        btnSnooze.visibility = if (AlarmService.canSnooze(this, payload, now)) View.VISIBLE else View.GONE
    }
}
