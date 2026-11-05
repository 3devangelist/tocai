package pt.tocai.app.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmActivity
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.sync.NewEventNotifier
import pt.tocai.app.util.AppForeground
import pt.tocai.app.util.TimeFormat
import java.lang.ref.WeakReference

/**
 * Marcações novas com a app aberta: em vez de notificação, pergunta num diálogo
 * "Pôr despertador?". A MainActivity liga-se em onStart/onStop para atualizar a lista.
 */
object NewEventPrompt {

    private const val TAG = "NewEventPrompt"
    private const val MULTI_THRESHOLD = 3
    private val main = Handler(Looper.getMainLooper())

    private var host: WeakReference<AppCompatActivity>? = null
    private var onChanged: (() -> Unit)? = null

    /** Eventos à espera de serem mostrados (só na thread principal). */
    private val queue = ArrayDeque<CalendarEvent>()
    private var showing = false
    @Volatile
    private var appCtx: Context? = null
    /** Outro diálogo (ex.: "Nova versão") está aberto: as marcações novas esperam (só na thread principal). */
    private var paused = false

    /** Chamado (thread principal) quando deixa de haver diálogos de marcação abertos ou à espera. */
    var onIdle: (() -> Unit)? = null

    /** Há um diálogo de marcação nova aberto ou à espera de aparecer (só na thread principal). */
    val isBusy: Boolean
        get() = showing || queue.isNotEmpty()

    /** Suspende/retoma os diálogos para não empilhar com outro diálogo (só na thread principal). */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        if (!value) showNext()
    }

    /** Chamar em onStart: [changed] é chamado (thread principal) depois de pôr um despertador. */
    fun attach(activity: AppCompatActivity, changed: () -> Unit) {
        host = WeakReference(activity)
        onChanged = changed
    }

    /** Chamar em onStop. */
    fun detach(activity: AppCompatActivity) {
        if (host?.get() === activity) {
            host = null
            onChanged = null
            onIdle = null
        }
    }

    /**
     * Chamado fora da thread principal. Se a app estiver em primeiro plano, mostra os eventos dentro
     * da app e devolve true; senão devolve false (quem chama publica a notificação).
     */
    fun tryDeliver(ctx: Context, events: List<CalendarEvent>): Boolean {
        if (events.isEmpty() || !AppForeground.isInForeground) return false
        val app = ctx.applicationContext ?: ctx
        appCtx = app
        main.post {
            val act = target()
            if (act == null) {
                // a app foi para segundo plano entretanto
                NewEventNotifier.postNotifications(app, events)
                return@post
            }
            onChanged?.invoke()
            if (events.size > MULTI_THRESHOLD && queue.isEmpty() && !showing && !paused) {
                showMulti(act, events)
            } else {
                queue.addAll(events.filter { e -> queue.none { it.key == e.key } })
                showNext()
            }
        }
        return true
    }

    private fun target(): AppCompatActivity? {
        val h = host?.get()
        if (h != null && usable(h) && h.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return h
        val cur = AppForeground.currentActivity as? AppCompatActivity ?: return null
        return if (usable(cur)) cur else null
    }

    private fun usable(a: Activity): Boolean =
        !a.isFinishing && !a.isDestroyed && a !is AlarmActivity

    private fun showNext() {
        if (showing || paused) return
        val e = queue.removeFirstOrNull()
        if (e == null) {
            onIdle?.invoke()
            return
        }
        val act = target()
        if (act == null) {
            val rest = listOf(e) + queue
            queue.clear()
            appCtx?.let { NewEventNotifier.postNotifications(it, rest) }
            return
        }
        showing = true
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.newevent_dialog_title)
                .setMessage(
                    act.getString(
                        R.string.newevent_dialog_message,
                        NewEventNotifier.displayTitle(act, e),
                        TimeFormat.relativeDay(e.begin),
                        TimeFormat.hm(e.begin),
                    )
                )
                .setPositiveButton(R.string.newevent_action_enable) { _, _ -> enable(act, listOf(e)) }
                .setNegativeButton(R.string.newevent_dialog_not_now, null)
                .setOnDismissListener {
                    showing = false
                    showNext()
                }
                .show()
        } catch (ex: Exception) {
            Log.w(TAG, "Falha a mostrar o diálogo", ex)
            showing = false
        }
    }

    private fun showMulti(act: AppCompatActivity, events: List<CalendarEvent>) {
        val labels = events.map {
            act.getString(
                R.string.newevent_multi_item,
                NewEventNotifier.displayTitle(act, it),
                TimeFormat.relativeDay(it.begin),
                TimeFormat.hm(it.begin),
            )
        }.toTypedArray()
        val checked = BooleanArray(events.size) { true }
        showing = true
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle(act.getString(R.string.newevent_summary_title, events.size))
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.newevent_multi_enable) { _, _ ->
                    val chosen = events.filterIndexed { i, _ -> checked[i] }
                    if (chosen.isNotEmpty()) enable(act, chosen)
                }
                .setNegativeButton(R.string.newevent_dialog_not_now, null)
                .setOnDismissListener {
                    showing = false
                    showNext()
                }
                .show()
        } catch (ex: Exception) {
            Log.w(TAG, "Falha a mostrar o diálogo", ex)
            showing = false
        }
    }

    private fun enable(act: Activity, events: List<CalendarEvent>) {
        val app = act.applicationContext
        Thread {
            val results = events.map { e ->
                try {
                    NewEventNotifier.enableAlarm(app, e.key, e.begin)
                } catch (ex: Exception) {
                    Log.w(TAG, "Falha a pôr o despertador", ex)
                    null
                }
            }
            main.post {
                val ok = results.filterNotNull()
                val msg = when {
                    ok.isEmpty() -> app.getString(R.string.newevent_enable_failed)
                    events.size == 1 -> app.getString(R.string.newevent_enabled, TimeFormat.hm(ok[0].triggerAt))
                    else -> app.resources.getQuantityString(R.plurals.newevent_enabled_many, ok.size, ok.size)
                }
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                onChanged?.invoke()
            }
        }.start()
    }
}
