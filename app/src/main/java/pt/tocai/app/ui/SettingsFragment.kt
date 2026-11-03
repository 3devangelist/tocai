package pt.tocai.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat as PickerTimeFormat
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.SettingsKeys
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.sync.DailySync
import pt.tocai.app.sync.SyncWorker

/** Ecrã de definições (res/xml/settings_preferences.xml). */
class SettingsFragment : PreferenceFragmentCompat() {

    // Chaves cuja alteração obriga a voltar a agendar
    private val syncKeys = setOf(
        SettingsKeys.DEFAULT_OFFSETS,
        SettingsKeys.AUTO_CALENDAR_IDS,
        SettingsKeys.CALENDAR_OFFSETS,
        SettingsKeys.INCLUDE_KEYWORDS,
        SettingsKeys.EXCLUDE_KEYWORDS,
        SettingsKeys.SKIP_DECLINED,
        SettingsKeys.LOOK_AHEAD_DAYS,
        SettingsKeys.RINGTONE_URI,
        SettingsKeys.VIBRATE,
        SettingsKeys.GRADUAL_VOLUME,
        SettingsKeys.SNOOZE_MINUTES,
        SettingsKeys.MAX_RING_MINUTES,
    )

    // Referência forte: as SharedPreferences só guardam referências fracas
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in syncKeys) {
            context?.let { SyncWorker.runNow(it) }
        }
        if (key == SettingsKeys.DAILY_SYNC_TIME || key == SettingsKeys.DAILY_SUMMARY) {
            context?.let {
                try {
                    DailySync.schedule(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha a reagendar o agendamento diário", e)
                }
            }
        }
    }

    private val ringtonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            val picked = pickedUri(data)
            // null ou o alarme por defeito = usar o alarme do sistema
            SettingsStore(ctx).ringtoneUri =
                if (picked == null || picked == Settings.System.DEFAULT_ALARM_ALERT_URI) null
                else picked.toString()
            updateRingtoneSummary()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_preferences, rootKey)

        findPreference<Preference>(KEY_CALENDARS)?.setOnPreferenceClickListener {
            val act = activity as? AppCompatActivity
            if (act != null) {
                CalendarPickerDialog.show(act) { updateCalendarsSummary() }
            }
            true
        }

        setupOffsets()
        setupKeywords(SettingsKeys.INCLUDE_KEYWORDS, R.string.settings_include_empty)
        setupKeywords(SettingsKeys.EXCLUDE_KEYWORDS, R.string.settings_exclude_empty)

        findPreference<Preference>(SettingsKeys.RINGTONE_URI)?.setOnPreferenceClickListener {
            openRingtonePicker()
            true
        }

        findPreference<Preference>(SettingsKeys.DAILY_SYNC_TIME)?.setOnPreferenceClickListener {
            openDailyTimePicker()
            true
        }

        findPreference<Preference>(KEY_TEST)?.setOnPreferenceClickListener {
            val ctx = requireContext()
            try {
                AlarmScheduler.scheduleTest(ctx)
                Toast.makeText(ctx, R.string.settings_test_scheduled, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "Falha a agendar o teste", e)
                Toast.makeText(ctx, R.string.settings_test_failed, Toast.LENGTH_LONG).show()
            }
            true
        }

        findPreference<Preference>(KEY_ABOUT)?.summary =
            getString(R.string.settings_about_summary, versionName(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        updateCalendarsSummary()
        updateRingtoneSummary()
        updateDailyTimeSummary()
        // depois de rodar o ecrã, o seletor de hora é recriado sem o listener
        (childFragmentManager.findFragmentByTag(TAG_TIME_PICKER) as? MaterialTimePicker)
            ?.let { attachTimePickerListener(it) }
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // --- minutos antes ---

    private fun setupOffsets() {
        val pref = findPreference<EditTextPreference>(SettingsKeys.DEFAULT_OFFSETS) ?: return
        pref.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_PHONE
            edit.setSelection(edit.text?.length ?: 0)
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val ctx = it.context
            ctx.getString(R.string.settings_offsets_summary, offsetsLabel(ctx, SettingsStore(ctx).defaultOffsets))
        }
        pref.setOnPreferenceChangeListener { p, newValue ->
            val raw = newValue as? String
            val parsed = SettingsStore.parseOffsets(raw).distinct().sortedDescending()
            if (parsed.isEmpty()) {
                Toast.makeText(requireContext(), R.string.settings_offsets_invalid, Toast.LENGTH_LONG).show()
                false
            } else {
                val normalized = parsed.joinToString(",")
                if (normalized == raw) {
                    true
                } else {
                    // guarda a versão limpa (ex.: "5, 15,x" -> "15,5")
                    (p as EditTextPreference).text = normalized
                    false
                }
            }
        }
    }

    // --- palavras-chave ---

    private fun setupKeywords(key: String, emptyRes: Int) {
        val pref = findPreference<EditTextPreference>(key) ?: return
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
            val text = p.text?.trim().orEmpty()
            if (text.isEmpty()) p.context.getString(emptyRes) else text
        }
    }

    // --- calendários ---

    private fun updateCalendarsSummary() {
        val pref = findPreference<Preference>(KEY_CALENDARS) ?: return
        val ctx = context ?: return
        val ids = SettingsStore(ctx).autoCalendarIds
        pref.summary = when {
            ids.isEmpty() -> getString(R.string.settings_calendars_none)
            else -> {
                val names = try {
                    CalendarRepository.calendars(ctx).filter { it.id in ids }.map { it.name }.distinct()
                } catch (e: Exception) {
                    Log.w(TAG, "Falha a ler calendários", e)
                    emptyList()
                }
                if (names.isEmpty()) {
                    resources.getQuantityString(R.plurals.settings_calendars_count, ids.size, ids.size)
                } else {
                    getString(R.string.settings_calendars_summary, names.joinToString(", "))
                }
            }
        }
    }

    // --- agendamento diário ---

    private fun openDailyTimePicker() {
        val ctx = context ?: return
        if (childFragmentManager.findFragmentByTag(TAG_TIME_PICKER) != null) return
        val (hour, minute) = SettingsStore(ctx).dailySyncHourMinute
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(PickerTimeFormat.CLOCK_24H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(R.string.daily_pref_time_picker_title)
            .build()
        attachTimePickerListener(picker)
        picker.show(childFragmentManager, TAG_TIME_PICKER)
    }

    private fun attachTimePickerListener(picker: MaterialTimePicker) {
        picker.clearOnPositiveButtonClickListeners()
        picker.addOnPositiveButtonClickListener {
            val c = context ?: return@addOnPositiveButtonClickListener
            SettingsStore(c).dailySyncTime = SettingsStore.formatTime(picker.hour, picker.minute)
            try {
                DailySync.schedule(c)
            } catch (e: Exception) {
                Log.w(TAG, "Falha a reagendar o agendamento diário", e)
            }
            updateDailyTimeSummary()
        }
    }

    private fun updateDailyTimeSummary() {
        val pref = findPreference<Preference>(SettingsKeys.DAILY_SYNC_TIME) ?: return
        val ctx = context ?: return
        pref.summary = getString(R.string.daily_pref_time_summary, SettingsStore(ctx).dailySyncTime)
    }

    // --- som ---

    private fun openRingtonePicker() {
        val ctx = context ?: return
        val current = SettingsStore(ctx).ringtoneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.settings_ringtone_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                current ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
            )
        }
        try {
            ringtonePicker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.settings_ringtone_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateRingtoneSummary() {
        val pref = findPreference<Preference>(SettingsKeys.RINGTONE_URI) ?: return
        val ctx = context ?: return
        val uriString = SettingsStore(ctx).ringtoneUri
        pref.summary = if (uriString == null) {
            getString(R.string.settings_ringtone_default)
        } else {
            val title = try {
                RingtoneManager.getRingtone(ctx, Uri.parse(uriString))?.getTitle(ctx)
            } catch (e: Exception) {
                // URI apagado ou sem acesso
                null
            }
            if (title.isNullOrBlank()) getString(R.string.settings_ringtone_unknown) else title
        }
    }

    private fun pickedUri(data: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            legacyPickedUri(data)
        }

    @Suppress("DEPRECATION")
    private fun legacyPickedUri(data: Intent): Uri? =
        data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

    // --- sobre ---

    private fun versionName(ctx: Context): String = try {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        info.versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    companion object {
        private const val TAG = "SettingsFragment"
        private const val KEY_CALENDARS = "settings_calendars"
        private const val KEY_TEST = "settings_test"
        private const val KEY_ABOUT = "settings_about"
        private const val TAG_TIME_PICKER = "daily_time_picker"

        /** "15 min e 5 min" / "1 h 30 min". */
        fun offsetsLabel(ctx: Context, offsets: List<Int>): String {
            val labels = offsets.map { minutesLabel(ctx, it) }
            return when (labels.size) {
                0 -> ""
                1 -> labels[0]
                else -> labels.dropLast(1).joinToString(", ") +
                    " " + ctx.getString(R.string.settings_list_and) + " " + labels.last()
            }
        }

        fun minutesLabel(ctx: Context, m: Int): String {
            val h = m / 60
            val r = m % 60
            return when {
                h == 0 -> ctx.getString(R.string.settings_minutes_short, m)
                r == 0 -> ctx.getString(R.string.settings_hours_short, h)
                else -> ctx.getString(R.string.settings_hours_minutes_short, h, r)
            }
        }
    }
}
