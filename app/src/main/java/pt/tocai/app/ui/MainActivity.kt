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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tocai.app.BuildConfig
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.PlannedAlarm
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.AlarmPlanner
import pt.tocai.app.update.UpdateActivity
import pt.tocai.app.update.UpdateInfo
import pt.tocai.app.update.UpdateOnOpen
import pt.tocai.app.update.UpdatePromptPolicy
import pt.tocai.app.util.AppForeground
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
    private lateinit var hintText: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    private lateinit var adapter: EventsAdapter
    private var loadJob: Job? = null

    // Pergunta "Nova versão" ao abrir: versão já respondida neste ecrã (0 = nenhuma), a encontrada e o diálogo
    private var updateAnswered = 0
    private var updateAnsweredOpen = -1   // AppForeground.openCount quando respondeu
    private var pendingUpdate: UpdateInfo? = null
    private var updateJob: Job? = null
    private var updateDialog: AlertDialog? = null

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

        updateAnswered = savedInstanceState?.getInt(STATE_UPDATE_ANSWERED, 0) ?: 0
        updateAnsweredOpen = savedInstanceState?.getInt(STATE_UPDATE_OPEN, -1) ?: -1
        setContentView(R.layout.main_activity)
        bindViews()

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.main_action_today -> { safeStart(MorningPickerActivity.intent(this)); true }
                R.id.main_action_calendars -> { openVisibleCalendars(); true }
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
            onOffsets = { row -> OffsetsDialog.show(this, row.event) { refresh() } },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null // evita "piscar" ao atualizar os interruptores

        // Pede logo as permissões de execução em falta (só na primeira criação)
        if (savedInstanceState == null) {
            val missing = missingRuntimePermissions()
            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
        }
    }

    override fun onStart() {
        super.onStart()
        NewEventPrompt.attach(this) { if (::adapter.isInitialized) refresh() }
        NewEventPrompt.onIdle = { maybeShowUpdatePrompt() }
    }

    override fun onStop() {
        NewEventPrompt.detach(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // onCreate pode ter terminado sem layout (onboarding)
        if (!::adapter.isInitialized) return
        refresh()
        checkForUpdateOnOpen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_UPDATE_ANSWERED, updateAnswered)
        outState.putInt(STATE_UPDATE_OPEN, updateAnsweredOpen)
    }

    override fun onDestroy() {
        // não deixar a janela do diálogo pendurada (rodar o ecrã volta a mostrá-lo, porque não foi respondido)
        updateDialog?.dismiss()
        updateDialog = null
        super.onDestroy()
    }

    // --- Nova versão ao abrir ----------------------------------------------

    /** Vê se há versão nova (GitHub no máximo 1×/10 min; senão usa a cache) e pergunta se quer atualizar. */
    private fun checkForUpdateOnOpen() {
        if (isFinishing || updateJob?.isActive == true) return
        if (!SettingsStore(this).autoUpdateCheck) return
        val app = applicationContext
        updateJob = lifecycleScope.launch {
            val found = try {
                withContext(Dispatchers.IO) { UpdateOnOpen.latest(app) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null // silencioso
            }
            pendingUpdate = found
            maybeShowUpdatePrompt()
        }
    }

    private fun maybeShowUpdatePrompt() {
        val info = pendingUpdate ?: return
        val busy = isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            updateDialog?.isShowing == true ||
            NewEventPrompt.isBusy // quando o diálogo da marcação fechar, onIdle volta a chamar isto
        val enabled = try { SettingsStore(this).autoUpdateCheck } catch (_: Exception) { false }
        // "Mais tarde" só vale até a app voltar a ser aberta (nova vinda para primeiro plano)
        val answered = UpdatePromptPolicy.answeredThisOpen(
            updateAnswered, updateAnsweredOpen, AppForeground.openCount,
        )
        if (!UpdatePromptPolicy.shouldPrompt(info.versionCode, BuildConfig.VERSION_CODE, answered, enabled, busy)) return
        val markAnswered = {
            updateAnswered = info.versionCode
            updateAnsweredOpen = AppForeground.openCount
        }
        val notes = UpdatePromptPolicy.summarizeNotes(info.notes)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_open_title, info.versionName))
            .setMessage(if (notes.isBlank()) getString(R.string.update_open_message_default) else notes)
            .setPositiveButton(R.string.update_open_now) { _, _ ->
                markAnswered()
                safeStart(UpdateActivity.installIntent(this, info))
            }
            .setNegativeButton(R.string.update_open_later) { _, _ -> markAnswered() }
            .setOnCancelListener { markAnswered() }
            .setOnDismissListener {
                updateDialog = null
                NewEventPrompt.setPaused(false)
            }
        try {
            NewEventPrompt.setPaused(true)
            updateDialog = builder.show()
        } catch (_: Exception) {
            markAnswered() // não insistir (evita voltar a tentar em ciclo)
            updateDialog = null
            NewEventPrompt.setPaused(false)
        }
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
        hintText = findViewById(R.id.main_hint)
        list = findViewById(R.id.main_list)
        empty = findViewById(R.id.main_empty)
    }

    /** Estado calculado fora da thread principal. */
    private class Snapshot(
        val hasCalendar: Boolean,
        val rows: List<EventsAdapter.Row>,
        val next: PlannedAlarm?,
        val defaultOffsets: List<Int>,
        val lookAheadDays: Int,
        val morningTime: String?,   // null = escolha da manhã desligada
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
        val morning = if (store.morningPicker) store.dailySyncTime else null
        val hasCal = CalendarRepository.hasPermission(app)
        if (!hasCal) {
            return Snapshot(false, emptyList(), null, defaults, days, morning)
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
        val hidden = store.hiddenCalendarIds
        // Calendários visíveis: só filtra a lista (um evento escolhido num calendário escondido continua a tocar)
        val events: List<CalendarEvent> = try {
            CalendarRepository.events(app, now, now + days * DAY_MS).filter { it.calendarId !in hidden }
        } catch (_: Exception) {
            emptyList()
        }

        val rows = ArrayList<EventsAdapter.Row>(events.size)
        var lastDay = Int.MIN_VALUE
        for (ev in events) {
            val auto = AlarmPlanner.isAutoEnabled(ev, rs)
            val enabled = AlarmPlanner.isEnabled(ev, rs)
            val offsets = AlarmPlanner.offsetsFor(ev, rs)
            val alarms = if (enabled && !ev.allDay) {
                offsets
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
                    offsets = offsets,
                    customOffsets = rs.eventOffsets.containsKey(ev.key),
                )
            )
        }

        return Snapshot(true, rows, plan.firstOrNull { it.triggerAt > now }, defaults, days, morning)
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
        hintText.text = if (s.morningTime != null) {
            getString(R.string.main_hint, s.morningTime)
        } else {
            getString(R.string.main_hint_no_morning)
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

    private fun openVisibleCalendars() {
        VisibleCalendarsDialog.show(this) { refresh() }
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
        const val STATE_UPDATE_ANSWERED = "update_answered"
        const val STATE_UPDATE_OPEN = "update_answered_open"
    }
}
