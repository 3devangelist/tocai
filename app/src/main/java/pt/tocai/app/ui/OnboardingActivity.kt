package pt.tocai.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.sync.SyncWorker
import pt.tocai.app.util.PermissionsHelper

/** Assistente da primeira utilização: permissões, calendários e teste. */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { CALENDAR, NOTIFICATIONS, EXACT, FULLSCREEN, BATTERY, CALENDARS, TEST }

    private class StepViews(
        val root: View,
        val icon: ImageView,
        val title: TextView,
        val text: TextView,
        val status: TextView,
        val button: MaterialButton,
        val secondary: MaterialButton,
        val check: ImageView,
    )

    private val stepViews = LinkedHashMap<Step, StepViews>()
    private lateinit var startButton: MaterialButton
    private lateinit var startHint: TextView

    private var manualMode = false
    private var tested = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val asked = prefs.getStringSet(KEY_ASKED, emptySet()).orEmpty().toMutableSet()
            asked.addAll(result.keys)
            prefs.edit().putStringSet(KEY_ASKED, asked).apply()
            if (result.values.any { !it }) {
                toast(getString(R.string.onboarding_permission_denied))
            }
            if (result[Manifest.permission.READ_CALENDAR] == true) {
                runSync()
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.onboarding_activity)

        val root = findViewById<View>(R.id.onboarding_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        manualMode = savedInstanceState?.getBoolean(STATE_MANUAL)
            ?: prefs.getBoolean(KEY_MANUAL, false)
        tested = savedInstanceState?.getBoolean(STATE_TESTED) ?: false

        startButton = findViewById(R.id.onboarding_start)
        startHint = findViewById(R.id.onboarding_start_hint)
        startButton.setOnClickListener { finishOnboarding() }

        buildSteps()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MANUAL, manualMode)
        outState.putBoolean(STATE_TESTED, tested)
    }

    // --- Construção dos passos ---

    private fun buildSteps() {
        val container = findViewById<LinearLayout>(R.id.onboarding_steps)
        container.removeAllViews()
        stepViews.clear()
        val inflater = LayoutInflater.from(this)
        for (step in Step.values()) {
            // Ecrã inteiro só precisa de autorização a partir da API 34
            if (step == Step.FULLSCREEN && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) continue
            val v = inflater.inflate(R.layout.onboarding_item_step, container, false)
            val views = StepViews(
                root = v,
                icon = v.findViewById(R.id.onboarding_step_icon),
                title = v.findViewById(R.id.onboarding_step_title),
                text = v.findViewById(R.id.onboarding_step_text),
                status = v.findViewById(R.id.onboarding_step_status),
                button = v.findViewById(R.id.onboarding_step_button),
                secondary = v.findViewById(R.id.onboarding_step_secondary),
                check = v.findViewById(R.id.onboarding_step_check),
            )
            views.icon.setImageResource(iconFor(step))
            views.title.setText(titleFor(step))
            views.text.setText(textFor(step))
            views.button.setOnClickListener { onStepAction(step) }
            if (step == Step.CALENDARS) {
                views.secondary.visibility = View.VISIBLE
                views.secondary.setText(R.string.onboarding_action_manual)
                views.secondary.setOnClickListener {
                    manualMode = true
                    prefs.edit().putBoolean(KEY_MANUAL, true).apply()
                    refresh()
                }
            }
            container.addView(v)
            stepViews[step] = views
        }
    }

    private fun iconFor(step: Step): Int = when (step) {
        Step.CALENDAR -> R.drawable.onboarding_ic_calendar
        Step.NOTIFICATIONS -> R.drawable.onboarding_ic_notifications
        Step.EXACT -> R.drawable.onboarding_ic_exact
        Step.FULLSCREEN -> R.drawable.onboarding_ic_fullscreen
        Step.BATTERY -> R.drawable.onboarding_ic_battery
        Step.CALENDARS -> R.drawable.onboarding_ic_calendars
        Step.TEST -> R.drawable.onboarding_ic_test
    }

    private fun titleFor(step: Step): Int = when (step) {
        Step.CALENDAR -> R.string.onboarding_step_calendar_title
        Step.NOTIFICATIONS -> R.string.onboarding_step_notifications_title
        Step.EXACT -> R.string.onboarding_step_exact_title
        Step.FULLSCREEN -> R.string.onboarding_step_fullscreen_title
        Step.BATTERY -> R.string.onboarding_step_battery_title
        Step.CALENDARS -> R.string.onboarding_step_calendars_title
        Step.TEST -> R.string.onboarding_step_test_title
    }

    private fun textFor(step: Step): Int = when (step) {
        Step.CALENDAR -> R.string.onboarding_step_calendar_text
        Step.NOTIFICATIONS -> R.string.onboarding_step_notifications_text
        Step.EXACT -> R.string.onboarding_step_exact_text
        Step.FULLSCREEN -> R.string.onboarding_step_fullscreen_text
        Step.BATTERY -> R.string.onboarding_step_battery_text
        Step.CALENDARS -> R.string.onboarding_step_calendars_text
        Step.TEST -> R.string.onboarding_step_test_text
    }

    // --- Estado ---

    private fun isDone(step: Step, store: SettingsStore, hasCalendar: Boolean): Boolean = when (step) {
        Step.CALENDAR -> hasCalendar
        Step.NOTIFICATIONS -> PermissionsHelper.notificationsEnabled(this)
        Step.EXACT -> PermissionsHelper.canScheduleExact(this)
        Step.FULLSCREEN -> PermissionsHelper.canUseFullScreen(this)
        Step.BATTERY -> PermissionsHelper.ignoringBatteryOptimizations(this)
        Step.CALENDARS -> store.autoCalendarIds.isNotEmpty() || manualMode
        Step.TEST -> tested
    }

    private fun refresh() {
        val store = SettingsStore(this)
        val hasCalendar = PermissionsHelper.hasCalendar(this)
        var allDone = true

        for ((step, v) in stepViews) {
            val done = try {
                isDone(step, store, hasCalendar)
            } catch (e: Exception) {
                Log.w(TAG, "Falha a verificar $step", e)
                false
            }
            if (!done && step != Step.BATTERY && step != Step.TEST) allDone = false
            v.check.visibility = if (done) View.VISIBLE else View.INVISIBLE
            v.status.visibility = View.GONE

            when (step) {
                Step.CALENDARS -> {
                    val count = store.autoCalendarIds.size
                    v.button.isEnabled = hasCalendar
                    v.button.setText(
                        if (count > 0) R.string.onboarding_action_change else R.string.onboarding_action_choose
                    )
                    v.secondary.visibility = if (count > 0 || manualMode) View.GONE else View.VISIBLE
                    val status = when {
                        !hasCalendar -> getString(R.string.onboarding_step_calendars_need_permission)
                        count > 0 -> resources.getQuantityString(R.plurals.onboarding_step_calendars_count, count, count)
                        manualMode -> getString(R.string.onboarding_step_calendars_manual)
                        else -> null
                    }
                    if (status != null) {
                        v.status.text = status
                        v.status.visibility = View.VISIBLE
                    }
                }
                Step.TEST -> {
                    v.button.isEnabled = true
                    v.button.setText(
                        if (tested) R.string.onboarding_action_test_again else R.string.onboarding_action_test
                    )
                }
                else -> {
                    v.button.isEnabled = !done
                    v.button.setText(
                        when {
                            done -> R.string.onboarding_action_done
                            step == Step.BATTERY -> R.string.onboarding_action_battery
                            else -> R.string.onboarding_action_allow
                        }
                    )
                    if (step == Step.BATTERY && !done) {
                        v.status.setText(R.string.onboarding_status_optional)
                        v.status.visibility = View.VISIBLE
                    }
                }
            }
        }

        // "Começar" só exige o passo dos calendários
        val calendarsReady = store.autoCalendarIds.isNotEmpty() || manualMode
        startButton.isEnabled = calendarsReady
        when {
            !calendarsReady -> {
                startHint.setText(R.string.onboarding_start_hint)
                startHint.visibility = View.VISIBLE
            }
            !allDone -> {
                startHint.setText(R.string.onboarding_start_warning)
                startHint.visibility = View.VISIBLE
            }
            else -> startHint.visibility = View.GONE
        }
    }

    // --- Ações ---

    private fun onStepAction(step: Step) {
        when (step) {
            Step.CALENDAR -> requestRuntime(
                arrayOf(Manifest.permission.READ_CALENDAR),
                PermissionsHelper.appSettingsIntent(this),
            )
            Step.NOTIFICATIONS -> {
                val needsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionsHelper.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)
                if (needsRuntime) {
                    requestRuntime(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        PermissionsHelper.notificationSettingsIntent(this),
                    )
                } else {
                    open(PermissionsHelper.notificationSettingsIntent(this))
                }
            }
            Step.EXACT -> open(PermissionsHelper.exactAlarmIntent(this))
            Step.FULLSCREEN -> open(
                PermissionsHelper.fullScreenIntent(this),
                PermissionsHelper.notificationSettingsIntent(this),
            )
            Step.BATTERY -> open(
                PermissionsHelper.batteryIntent(this),
                PermissionsHelper.batteryListIntent(),
            )
            Step.CALENDARS -> {
                if (isFinishing || isDestroyed) return
                CalendarPickerDialog.show(this) {
                    if (SettingsStore(this).autoCalendarIds.isNotEmpty()) {
                        manualMode = false
                        prefs.edit().putBoolean(KEY_MANUAL, false).apply()
                    }
                    runSync()
                    refresh()
                }
            }
            Step.TEST -> runTest()
        }
    }

    /** Pede permissões em tempo de execução; se já foram recusadas de vez, abre as definições. */
    private fun requestRuntime(perms: Array<String>, settings: Intent) {
        val missing = perms.filter { !PermissionsHelper.isGranted(this, it) }
        if (missing.isEmpty()) {
            refresh()
            return
        }
        val asked = prefs.getStringSet(KEY_ASKED, emptySet()).orEmpty()
        val blocked = missing.any {
            asked.contains(it) && !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (blocked) {
            open(settings)
        } else {
            try {
                permissionLauncher.launch(missing.toTypedArray())
            } catch (e: Exception) {
                Log.w(TAG, "Falha a pedir permissões", e)
                open(settings)
            }
        }
    }

    private fun open(vararg intents: Intent) {
        if (!PermissionsHelper.startFirstAvailable(this, *intents)) {
            toast(getString(R.string.onboarding_open_failed))
        }
    }

    private fun runTest() {
        if (!PermissionsHelper.canScheduleExact(this)) {
            toast(getString(R.string.onboarding_test_no_exact))
            refresh()
            return
        }
        try {
            AlarmScheduler.scheduleTest(this, 10)
            tested = true
            toast(getString(R.string.onboarding_test_scheduled))
        } catch (e: Exception) {
            Log.w(TAG, "Falha a agendar teste", e)
            toast(getString(R.string.onboarding_test_failed))
        }
        refresh()
    }

    private fun runSync() {
        try {
            SyncWorker.runNow(this)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a sincronizar", e)
        }
    }

    private fun finishOnboarding() {
        val store = SettingsStore(this)
        if (store.autoCalendarIds.isEmpty() && !manualMode) {
            refresh()
            return
        }
        store.onboardingDone = true
        prefs.edit().remove(KEY_MANUAL).apply()
        runSync()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "OnboardingActivity"
        const val PREFS_NAME = "tocai_onboarding"
        const val KEY_MANUAL = "manual_mode"
        const val KEY_ASKED = "asked_permissions"
        const val STATE_MANUAL = "state_manual"
        const val STATE_TESTED = "state_tested"
    }
}
