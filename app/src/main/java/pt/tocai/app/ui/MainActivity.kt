package pt.tocai.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.PlannedAlarm
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.AlarmPlanner
import pt.tocai.app.util.PermissionsHelper
import pt.tocai.app.util.TimeFormat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var warningCard: MaterialCardView
    private lateinit var warningText: TextView
    private lateinit var warningButton: MaterialButton
    private lateinit var nextTime: TextView
    private lateinit var nextDetail: TextView
    private lateinit var defaultOffsetsText: TextView
    private lateinit var autoText: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    private lateinit var adapter: EventsAdapter
    private var loadJob: Job? = null

    // true quando o utilizador recusou "não voltar a perguntar": o "Resolver" passa a abrir as definições
    private var runtimePermanentlyDenied = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isNotEmpty()) {
                runtimePermanentlyDenied = denied.any {
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }
                if (denied.contains(android.Manifest.permission.READ_CALENDAR)) {
                    toast(getString(R.string.main_permissions_denied), long = true)
                }
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Primeira utilização: assistente de configuração
        val store = SettingsStore(this)
        if (!store.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.main_activity)
        bindViews()

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.main_action_calendars -> { openCalendarPicker(); true }
                R.id.main_action_test -> { testAlarm(); true }
                R.id.main_action_settings -> {
                    safeStart(Intent(this, SettingsActivity::class.java)); true
                }
                else -> false
            }
        }

        adapter = EventsAdapter(
            onToggle = { row, checked -> toggleEvent(row, checked) },
            onClock = { row -> sendToClockApp(row) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null // evita "piscar" ao atualizar os interruptores

        findViewById<View>(R.id.main_auto_row).setOnClickListener { openCalendarPicker() }
        findViewById<View>(R.id.main_auto_button).setOnClickListener { openCalendarPicker() }

        // Pede logo as permissões de execução em falta (só na primeira criação)
        if (savedInstanceState == null) {
            val missing = missingRuntimePermissions()
            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
        }
    }

    override fun onResume() {
        super.onResume()
        // onCreate pode ter terminado sem layout (onboarding)
        if (!::adapter.isInitialized) return
        refresh()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.main_toolbar)
        progress = findViewById(R.id.main_progress)
        warningCard = findViewById(R.id.main_warning_card)
        warningText = findViewById(R.id.main_warning_text)
        warningButton = findViewById(R.id.main_warning_button)
        nextTime = findViewById(R.id.main_next_time)
        nextDetail = findViewById(R.id.main_next_detail)
        defaultOffsetsText = findViewById(R.id.main_default_offsets)
        autoText = findViewById(R.id.main_auto_text)
        list = findViewById(R.id.main_list)
        empty = findViewById(R.id.main_empty)
    }

    /** Estado calculado fora da thread principal. */
    private class Snapshot(
        val hasCalendar: Boolean,
        val rows: List<EventsAdapter.Row>,
        val next: PlannedAlarm?,
        val autoNames: List<String>,
        val defaultOffsets: List<Int>,
        val lookAheadDays: Int,
    )

    /** Sincroniza os despertadores e volta a desenhar o ecrã. */
    private fun refresh() {
        updateWarning()
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            val app = applicationContext
            val snap = withContext(Dispatchers.IO) { load(app) }
            progress.visibility = View.INVISIBLE
            render(snap)
            updateWarning()
        }
    }

    private fun load(app: Context): Snapshot {
        val now = System.currentTimeMillis()
        val store = SettingsStore(app)
        val days = store.lookAheadDays.coerceIn(1, 31)
        val defaults = store.defaultOffsets
        val hasCal = CalendarRepository.hasPermission(app)
        if (!hasCal) {
            return Snapshot(false, emptyList(), null, emptyList(), defaults, days)
        }

        // Agenda (ou reagenda) e lê o plano atual
        try {
            AlarmScheduler.syncNow(app)
        } catch (_: Exception) {
            // sem permissões ou falha do AlarmManager: o aviso trata disso
        }
        val plan = try {
            AlarmScheduler.currentPlan(app)
        } catch (_: Exception) {
            emptyList()
        }

        val rs = store.toRuleSettings()
        val events: List<CalendarEvent> = try {
            CalendarRepository.events(app, now, now + days * DAY_MS)
        } catch (_: Exception) {
            emptyList()
        }

        val rows = ArrayList<EventsAdapter.Row>(events.size)
        var lastDay = Int.MIN_VALUE
        for (ev in events) {
            val auto = AlarmPlanner.isAutoEnabled(ev, rs)
            val enabled = AlarmPlanner.isEnabled(ev, rs)
            val alarms = if (enabled && !ev.allDay) {
                AlarmPlanner.offsetsFor(ev, rs)
                    .map { EventsAdapter.AlarmTime(ev.begin - it * MINUTE_MS, it) }
                    .sortedBy { it.triggerAt }
            } else {
                emptyList()
            }
            // Eventos já a decorrer agrupam-se em "hoje"
            val dayTime = if (ev.begin < now) now else ev.begin
            val key = TimeFormat.dayKey(dayTime)
            val header = if (key != lastDay) {
                TimeFormat.relativeDay(dayTime, now).replaceFirstChar { it.titlecase() }
            } else {
                null
            }
            lastDay = key
            rows.add(
                EventsAdapter.Row(
                    event = ev,
                    autoEnabled = auto,
                    enabled = enabled,
                    forcedOn = ev.key in rs.forcedOn && !auto,
                    forcedOff = ev.key in rs.forcedOff && auto,
                    alarms = alarms,
                    dayHeader = header,
                )
            )
        }

        val autoIds = store.autoCalendarIds
        val autoNames = if (autoIds.isEmpty()) {
            emptyList()
        } else {
            try {
                CalendarRepository.calendars(app).filter { it.id in autoIds }.map { it.name }
            } catch (_: Exception) {
                emptyList()
            }
        }

        return Snapshot(true, rows, plan.firstOrNull { it.triggerAt > now }, autoNames, defaults, days)
    }

    private fun render(s: Snapshot) {
        // Cartão "Próximo despertador"
        val next = s.next
        if (next != null) {
            nextTime.text = TimeFormat.hm(next.triggerAt)
            val day = TimeFormat.relativeDay(next.triggerAt).replaceFirstChar { it.titlecase() }
            nextDetail.text = if (next.offsetMinutes == 0) {
                getString(R.string.main_next_detail_at_start, day, next.event.title)
            } else {
                getString(R.string.main_next_detail, day, TimeFormat.minutesLabel(next.offsetMinutes), next.event.title)
            }
        } else {
            nextTime.text = getString(R.string.main_next_none_time)
            nextDetail.text = getString(R.string.main_next_none)
        }
        defaultOffsetsText.text = getString(
            R.string.main_default_offsets,
            joinHuman(s.defaultOffsets.map { TimeFormat.minutesLabel(it) }),
        )
        autoText.text = if (s.autoNames.isEmpty()) {
            getString(R.string.main_auto_none)
        } else {
            getString(R.string.main_auto_in, s.autoNames.joinToString(getString(R.string.main_list_separator)))
        }

        // Lista
        adapter.submit(s.rows)
        if (s.rows.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (!s.hasCalendar) {
                getString(R.string.main_empty_no_permission)
            } else {
                resources.getQuantityString(R.plurals.main_empty_days, s.lookAheadDays, s.lookAheadDays)
            }
        } else {
            empty.visibility = View.GONE
        }
    }

    /** "5 min", "15 min e 5 min", "1 h, 15 min e 5 min". */
    private fun joinHuman(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(getString(R.string.main_list_separator)) +
            getString(R.string.main_list_last_separator) + parts.last()
    }

    // --- Permissões ---------------------------------------------------------

    private fun missingRuntimePermissions(): Array<String> =
        try {
            PermissionsHelper.runtimePermissionsNeeded(this)
        } catch (_: Exception) {
            emptyArray()
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    private fun updateWarning() {
        val problem = try {
            PermissionsHelper.firstProblem(this)
        } catch (_: Exception) {
            null
        }
        if (problem == null) {
            warningCard.visibility = View.GONE
            warningButton.setOnClickListener(null)
            return
        }
        warningCard.visibility = View.VISIBLE
        warningText.text = problem.message
        warningButton.text = problem.action.ifBlank { getString(R.string.main_warning_resolve) }
        warningButton.setOnClickListener {
            val target = problem.intent
            val missing = problem.runtimePermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            when {
                missing.isNotEmpty() && !runtimePermanentlyDenied ->
                    permissionLauncher.launch(missing.toTypedArray())
                target != null -> safeStart(target)
                else -> safeStart(PermissionsHelper.appSettingsIntent(this))
            }
        }
    }

    // --- Ações -------------------------------------------------------------

    private fun openCalendarPicker() {
        CalendarPickerDialog.show(this) { refresh() }
    }

    private fun testAlarm() {
        if (!AlarmScheduler.canScheduleExact(this)) {
            toast(getString(R.string.main_test_no_permission), long = true)
            updateWarning()
            return
        }
        try {
            AlarmScheduler.scheduleTest(this, 10)
            toast(getString(R.string.main_test_scheduled), long = true)
        } catch (_: Exception) {
            toast(getString(R.string.main_test_failed), long = true)
        }
    }

    private fun toggleEvent(row: EventsAdapter.Row, on: Boolean) {
        if (on == row.enabled) return
        SettingsStore(this).setEventEnabled(row.event, on, row.autoEnabled)
        refresh() // refresh chama syncNow
    }

    /** Alternativa: cria o alarme na app Relógio do Android (só nas próximas 24 h). */
    private fun sendToClockApp(row: EventsAdapter.Row) {
        val now = System.currentTimeMillis()
        val at = row.futureAlarms(now).firstOrNull()?.triggerAt
        if (at == null) {
            toast(getString(R.string.main_clock_passed))
            return
        }
        if (at - now > DAY_MS) {
            toast(getString(R.string.main_clock_too_far), long = true)
            return
        }
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_MESSAGE, row.event.title.take(60))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        try {
            startActivity(intent)
            toast(getString(R.string.main_clock_created, TimeFormat.hm(at)))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.main_clock_none), long = true)
        } catch (_: SecurityException) {
            toast(getString(R.string.main_clock_none), long = true)
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.main_open_failed))
        } catch (_: SecurityException) {
            toast(getString(R.string.main_open_failed))
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * MINUTE_MS
    }
}
