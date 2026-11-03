package pt.ricardo.caldespertador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ecrã de despertador, aparece mesmo com o telefone bloqueado. */
class AlarmActivity : AppCompatActivity() {

    companion object { const val ACTION_FINISH = "pt.ricardo.caldespertador.FINISH_ALARM" }

    private val finisher = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
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
        setContentView(R.layout.activity_alarm)
        bind(intent)

        findViewById<Button>(R.id.btnStop).setOnClickListener { send(AlarmService.ACTION_STOP) }
        findViewById<Button>(R.id.btnSnooze).setOnClickListener { send(AlarmService.ACTION_SNOOZE) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* obrigar a escolher Parar ou Adiar */ }
        })
        ContextCompat.registerReceiver(
            this, finisher, IntentFilter(ACTION_FINISH), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bind(intent)
    }

    private fun bind(i: Intent) {
        val begin = i.getLongExtra(AlarmScheduler.EXTRA_BEGIN, System.currentTimeMillis())
        val fmt = SimpleDateFormat("HH:mm", Locale("pt", "PT"))
        findViewById<TextView>(R.id.txtNow).text = fmt.format(Date())
        findViewById<TextView>(R.id.txtTitle).text = i.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: ""
        val loc = i.getStringExtra(AlarmScheduler.EXTRA_LOCATION).orEmpty()
        findViewById<TextView>(R.id.txtWhen).text =
            "Começa às ${fmt.format(Date(begin))}" + if (loc.isNotBlank()) "\n$loc" else ""
        findViewById<Button>(R.id.btnSnooze).text = "Adiar ${AlarmService.SNOOZE_MIN} min"
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(finisher)
        super.onDestroy()
    }
}
