package pt.tocai.app.update

import android.content.Intent
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import pt.tocai.app.BuildConfig
import pt.tocai.app.R
import pt.tocai.app.data.SettingsKeys

/** Liga as preferências de atualizações no ecrã de Definições (chamado pelo SettingsFragment). */
object UpdatePreferences {

    const val KEY_CHECK = "update_check_now"

    fun setup(fragment: PreferenceFragmentCompat) {
        fragment.findPreference<Preference>(KEY_CHECK)?.apply {
            summary = context.getString(R.string.update_pref_check_summary, BuildConfig.VERSION_NAME)
            setOnPreferenceClickListener {
                fragment.startActivity(Intent(fragment.requireContext(), UpdateActivity::class.java))
                true
            }
        }
        fragment.findPreference<Preference>(SettingsKeys.AUTO_UPDATE_CHECK)?.setOnPreferenceChangeListener { p, newValue ->
            try {
                UpdateCheckWorker.schedule(p.context, newValue as? Boolean ?: true)
            } catch (e: Exception) {
                Log.w("UpdatePreferences", "Falha a reagendar a verificação de atualizações", e)
            }
            true
        }
    }
}
