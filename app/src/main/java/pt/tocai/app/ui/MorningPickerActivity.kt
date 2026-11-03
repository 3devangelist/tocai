package pt.tocai.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.AlarmPlanner
import pt.tocai.app.rules.PendingOffsets
import pt.tocai.app.util.TimeFormat
import java.util.Calendar

/**
 * "Bom dia! O que tens hoje?": os eventos de hoje (de agora até ao fim do dia, só dos calendários
 * visíveis) com uma caixa por evento. Abre-se sozinha à hora da escolha diária (fullScreenIntent da
 * notificação publicada pelo DailySyncReceiver) ou pelo menu da MainActivity.
 * Não usa showWhenLocked: com o telemóvel bloqueado, fica à espera por trás do bloqueio e aparece
 * quando o utilizador desbloqueia.
 */
class MorningPickerActivity : AppCompatActivity() {

    private lateinit var headTitle: TextView
    private lateinit var date: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var confirm: MaterialButton
    private lateinit var tomorrowButton: MaterialButton
    private lateinit var dismiss: MaterialButton

    private val adapter = Adapter()
    private var loadJob: Job? = null
    private var showTomorrow = false
    private var busy = false

    /** Escolhas do utilizador ainda por confirmar (CalendarEvent.key -> marcado). */
    private val pending = HashMap<String, Boolean>()

    /** Mudanças de minutos antes ainda por confirmar (CalendarEvent.key -> minutos; [] = como os outros). */
    private val pendingOffsets = LinkedHashMap<String, List<Int>>()

    private class Row(
        val event: CalendarEvent,
        val autoEnabled: Boolean,
        val enabled: Boolean,
        val triggers: List<Pair<Long, Int>>,   // (triggerAt, minutos antes), só os futuros
        val allOffsetsPast: Boolean,
    ) {
        val selectable: Boolean get() = !event.allDay && triggers.isNotEmpty()
    }

    private class Snapshot(val hasCalendar: Boolean, val rows: List<Row>, val dayStart: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.morning_activity)
        headTitle = findViewById(R.id.morning_title)
        date = findViewById(R.id.morning_date)
        subtitle = findViewById(R.id.morning_subtitle)
        progress = findViewById(R.id.morning_progress)
        list = findViewById(R.id.morning_list)
        empty = findViewById(R.id.morning_empty)
        confirm = findViewById(R.id.morning_confirm)
        tomorrowButton = findViewById(R.id.morning_tomorrow)
        dismiss = findViewById(R.id.morning_dismiss)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        showTomorrow = savedInstanceState?.getBoolean(STATE_TOMORROW) ?: false
        savedInstanceState?.getStringArrayList(STATE_ON)?.forEach { pending[it] = true }
        savedInstanceState?.getStringArrayList(STATE_OFF)?.forEach { pending[it] = false }
        pendingOffsets.putAll(
            PendingOffsets.decode(
                savedInstanceState?.getStringArrayList(STATE_OFFSET_KEYS),
                savedInstanceState?.getStringArrayList(STATE_OFFSET_VALUES),
            )
        )

        confirm.setOnClickListener { applyChoices() }
        dismiss.setOnClickListener { finish() }
        tomorrowButton.setOnClickListener {
            showTomorrow = !showTomorrow
            pending.clear()
            pendingOffsets.clear()
            load()
        }
        cancelNotification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // aberta de novo pela notificação: volta a hoje
        showTomorrow = false
        pending.clear()
        pendingOffsets.clear()
        cancelNotification()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TOMORROW, showTomorrow)
        outState.putStringArrayList(STATE_ON, ArrayList(pending.filterValues { it }.keys))
        outState.putStringArrayList(STATE_OFF, ArrayList(pending.filterValues { !it }.keys))
        val (offsetKeys, offsetValues) = PendingOffsets.encode(pendingOffsets)
        outState.putStringArrayList(STATE_OFFSET_KEYS, offsetKeys)
        outState.putStringArrayList(STATE_OFFSET_VALUES, offsetValues)
    }

    private fun cancelNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    // --- dados ---

    private fun load() {
        if (busy) return
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        val tomorrow = showTomorrow
        val offsets = HashMap(pendingOffsets)
        loadJob = lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { compute(applicationContext, tomorrow, offsets) }
            progress.visibility = View.INVISIBLE
            render(snap)
        }
    }

    private fun compute(app: Context, tomorrow: Boolean, offsets: Map<String, List<Int>>): Snapshot {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now, 0)
        val from = if (tomorrow) startOfDay(now, 1) else now
        val to = if (tomorrow) startOfDay(now, 2) else startOfDay(now, 1)
        val dayStart = if (tomorrow) from else todayStart
        if (!CalendarRepository.hasPermission(app)) return Snapshot(false, emptyList(), dayStart)
        val store = SettingsStore(app)
        val saved = store.toRuleSettings()
        // mostra já as mudanças de minutos ainda por confirmar
        val rs = saved.copy(eventOffsets = PendingOffsets.merge(saved.eventOffsets, offsets))
        val events = try {
            CalendarRepository.events(app, from, to)
        } catch (_: Exception) {
            emptyList()
        }
        val rows = events
            .filter { store.isCalendarVisible(it.calendarId) }
            .filter { it.allDay || it.begin > now }
            .map { ev ->
                val offsets = AlarmPlanner.offsetsFor(ev, rs)
                val triggers = offsets
                    .map { (ev.begin - it * MINUTE_MS) to it }
                    .filter { it.first > now }
                    .sortedBy { it.first }
                Row(
                    event = ev,
                    autoEnabled = AlarmPlanner.isAutoEnabled(ev, rs),
                    enabled = AlarmPlanner.isEnabled(ev, rs),
                    triggers = triggers,
                    allOffsetsPast = !ev.allDay && triggers.isEmpty(),
                )
            }
            // dia inteiro primeiro, depois por hora
            .sortedWith(compareBy<Row>({ !it.event.allDay }, { it.event.begin }))
        return Snapshot(true, rows, dayStart)
    }

    private fun render(s: Snapshot) {
        headTitle.setText(if (showTomorrow) R.string.morning_title_tomorrow else R.string.morning_title)
        date.text = TimeFormat.relativeDay(s.dayStart).replaceFirstChar { it.titlecase() }
        tomorrowButton.setText(if (showTomorrow) R.string.morning_show_today else R.string.morning_show_tomorrow)
        adapter.submit(s.rows)

        val hasSelectable = s.rows.any { it.selectable }
        if (s.rows.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.setText(
                when {
                    !s.hasCalendar -> R.string.morning_no_permission
                    showTomorrow -> R.string.morning_empty_tomorrow
                    else -> R.string.morning_empty_today
                }
            )
        } else {
            empty.visibility = View.GONE
        }
        subtitle.visibility = if (hasSelectable) View.VISIBLE else View.GONE
        confirm.visibility = if (hasSelectable) View.VISIBLE else View.GONE
        dismiss.setText(if (hasSelectable) R.string.morning_not_now else R.string.morning_close)
        tomorrowButton.visibility = if (s.hasCalendar) View.VISIBLE else View.GONE
    }

    private fun isChecked(row: Row): Boolean = pending[row.event.key] ?: row.enabled

    // --- confirmar ---

    private fun applyChoices() {
        if (busy) return
        busy = true
        confirm.isEnabled = false
        progress.visibility = View.VISIBLE
        val rows = adapter.rows.filter { it.selectable }
        val choices = rows.map { it to isChecked(it) }
        val offsets = HashMap(pendingOffsets)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val app = applicationContext
                val store = SettingsStore(app)
                for ((key, list) in offsets) {
                    try {
                        store.setEventOffsets(key, list.ifEmpty { null })
                    } catch (_: Exception) {
                    }
                }
                for ((row, on) in choices) {
                    if (on != row.enabled) store.setEventEnabled(row.event, on, row.autoEnabled)
                }
                val plan = try {
                    AlarmScheduler.syncNow(app)
                } catch (_: Exception) {
                    emptyList()
                }
                val keys = choices.filter { it.second }.map { it.first.event.key }.toSet()
                plan.count { it.event.key in keys }
            }
            progress.visibility = View.INVISIBLE
            val selected = choices.count { it.second }
            val msg = when {
                selected > 0 && count == 0 && !AlarmScheduler.canScheduleExact(this@MorningPickerActivity) ->
                    getString(R.string.morning_no_exact)
                count == 0 -> getString(R.string.morning_none_scheduled)
                else -> resources.getQuantityString(R.plurals.morning_scheduled, count, count)
            }
            Toast.makeText(this@MorningPickerActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun changeOffsets(row: Row) {
        if (busy) return
        val key = row.event.key
        val current = PendingOffsets.current(key, SettingsStore(this).eventOffsets(key), pendingOffsets)
        // só fica em memória; é gravado em "Confirmar despertadores" ("Agora não" descarta)
        OffsetsDialog.pick(this, row.event, current) { picked ->
            pendingOffsets[key] = PendingOffsets.choice(picked)
            load()
        }
    }

    // --- lista ---

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        var rows: List<Row> = emptyList()
            private set

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.morning_item_event, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rows[position])
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val check: MaterialCheckBox = v.findViewById(R.id.morning_item_check)
        private val color: View = v.findViewById(R.id.morning_item_color)
        private val time: TextView = v.findViewById(R.id.morning_item_time)
        private val titleView: TextView = v.findViewById(R.id.morning_item_title)
        private val meta: TextView = v.findViewById(R.id.morning_item_meta)
        private val alarm: MaterialButton = v.findViewById(R.id.morning_item_alarm)

        fun bind(row: Row) {
            val ev = row.event
            color.backgroundTintList = ColorStateList.valueOf(ev.color or OPAQUE)
            time.text = if (ev.allDay) {
                getString(R.string.main_all_day)
            } else {
                getString(R.string.main_time_range, TimeFormat.hm(ev.begin), TimeFormat.hm(ev.end))
            }
            titleView.text = ev.title
            val metaParts = ArrayList<String>(2)
            if (ev.calendarName.isNotBlank()) metaParts.add(ev.calendarName)
            if (ev.location.isNotBlank()) metaParts.add(ev.location)
            meta.text = metaParts.joinToString(getString(R.string.main_meta_separator))
            meta.visibility = if (metaParts.isEmpty()) View.GONE else View.VISIBLE

            check.setOnCheckedChangeListener(null)
            if (row.selectable) {
                check.visibility = View.VISIBLE
                check.isEnabled = true
                check.isChecked = isChecked(row)
                check.setOnCheckedChangeListener { _, isChecked ->
                    pending[row.event.key] = isChecked
                    bindAlarm(row)
                }
                itemView.setOnClickListener { check.toggle() }
                itemView.alpha = 1f
            } else {
                check.visibility = View.INVISIBLE
                check.isChecked = false
                itemView.setOnClickListener(null)
                itemView.isClickable = false
                itemView.alpha = 0.6f
            }
            bindAlarm(row)
        }

        private fun bindAlarm(row: Row) {
            val ev = row.event
            when {
                ev.allDay -> {
                    alarm.text = getString(R.string.morning_all_day)
                    alarm.isEnabled = false
                    alarm.setOnClickListener(null)
                }
                row.allOffsetsPast -> {
                    alarm.text = getString(R.string.morning_alarm_passed)
                    alarm.isEnabled = true
                    alarm.setOnClickListener { changeOffsets(row) }
                }
                else -> {
                    alarm.text = row.triggers.joinToString(getString(R.string.main_meta_separator)) { (at, off) ->
                        if (off == 0) getString(R.string.morning_alarm_at_start, TimeFormat.hm(at))
                        else getString(R.string.morning_alarm_at, TimeFormat.hm(at), TimeFormat.minutesLabel(off))
                    }
                    alarm.isEnabled = true
                    alarm.setOnClickListener { changeOffsets(row) }
                }
            }
            // a hora do despertador fica esbatida enquanto o evento não estiver marcado
            alarm.alpha = if (row.selectable && !isChecked(row)) 0.6f else 1f
        }
    }

    private fun startOfDay(now: Long, plusDays: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, plusDays)
    }.timeInMillis

    companion object {
        /** Id da notificação "Escolha da manhã" (publicada pelo DailySyncReceiver). */
        const val NOTIFICATION_ID = 7010
        const val EXTRA_FROM_NOTIFICATION = "pt.tocai.app.extra.FROM_MORNING_NOTIFICATION"

        private const val STATE_TOMORROW = "state_tomorrow"
        private const val STATE_ON = "state_on"
        private const val STATE_OFF = "state_off"
        private const val STATE_OFFSET_KEYS = "state_offset_keys"
        private const val STATE_OFFSET_VALUES = "state_offset_values"
        private const val MINUTE_MS = 60_000L
        private const val OPAQUE: Int = -0x1000000

        fun intent(ctx: Context): Intent = Intent(ctx, MorningPickerActivity::class.java)
    }
}
