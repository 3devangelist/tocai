package pt.tocai.app.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pt.tocai.app.BuildConfig
import pt.tocai.app.R

/** Mostra a versão atual e a nova, as notas da release e instala a atualização. */
class UpdateActivity : AppCompatActivity() {

    private lateinit var current: TextView
    private lateinit var newVersion: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var button: MaterialButton
    private lateinit var notesTitle: TextView
    private lateinit var notes: TextView

    private var info: UpdateInfo? = null
    private var job: Job? = null
    /** O utilizador foi dar a permissão de instalar: ao voltar, continua sozinho. */
    private var waitingPermission = false

    private val installListener: (UpdateInstaller.Status) -> Unit = { s ->
        when (s) {
            is UpdateInstaller.Status.Success -> {
                hideProgress()
                status.setText(R.string.update_status_done)
                button.visibility = View.GONE
            }
            is UpdateInstaller.Status.Failure -> showError(s.message, retryInstall = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.update_activity)

        val root = findViewById<View>(R.id.update_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.update_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.update_title)

        current = findViewById(R.id.update_current)
        newVersion = findViewById(R.id.update_new)
        status = findViewById(R.id.update_status)
        progress = findViewById(R.id.update_progress)
        button = findViewById(R.id.update_button)
        notesTitle = findViewById(R.id.update_notes_title)
        notes = findViewById(R.id.update_notes)

        current.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        UpdateInstaller.statusListener = installListener
        check()
    }

    override fun onResume() {
        super.onResume()
        if (waitingPermission) {
            waitingPermission = false
            if (UpdateInstaller.canInstall(this)) {
                info?.let { startInstall(it) }
            } else {
                status.setText(R.string.update_permission_still_missing)
            }
        }
    }

    override fun onDestroy() {
        if (UpdateInstaller.statusListener === installListener) UpdateInstaller.statusListener = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // --- verificar ---

    private fun check() {
        job?.cancel()
        info = null
        newVersion.visibility = View.GONE
        notesTitle.visibility = View.GONE
        notes.visibility = View.GONE
        button.visibility = View.GONE
        status.setText(R.string.update_status_checking)
        showIndeterminate()
        job = lifecycleScope.launch {
            try {
                val found = UpdateChecker.check()
                hideProgress()
                if (found == null) {
                    status.setText(R.string.update_status_latest)
                } else {
                    showAvailable(found)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha a procurar atualizações", e)
                showError(getString(R.string.update_error_check, e.message ?: "?"), retryInstall = false)
            }
        }
    }

    private fun showAvailable(found: UpdateInfo) {
        info = found
        newVersion.text = getString(R.string.update_new_version, found.versionName)
        newVersion.visibility = View.VISIBLE
        status.setText(R.string.update_status_available)
        if (found.notes.isNotBlank()) {
            notes.text = found.notes
            notesTitle.visibility = View.VISIBLE
            notes.visibility = View.VISIBLE
        }
        button.setText(R.string.update_button_install)
        button.isEnabled = true
        button.visibility = View.VISIBLE
        button.setOnClickListener { onInstallClicked(found) }
    }

    // --- instalar ---

    private fun onInstallClicked(found: UpdateInfo) {
        if (!UpdateInstaller.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    try {
                        waitingPermission = true
                        startActivity(UpdateInstaller.unknownSourcesIntent(this))
                    } catch (e: ActivityNotFoundException) {
                        waitingPermission = false
                        openReleasePage(found)
                    }
                }
                .setNegativeButton(R.string.update_cancel, null)
                .show()
            return
        }
        startInstall(found)
    }

    private fun startInstall(found: UpdateInfo) {
        job?.cancel()
        button.isEnabled = false
        status.setText(R.string.update_status_downloading)
        showIndeterminate()
        job = lifecycleScope.launch {
            try {
                val apk = UpdateInstaller.download(this@UpdateActivity, found.apkUrl) { pct ->
                    runOnUiThread { showPercent(pct) }
                }
                status.setText(R.string.update_status_installing)
                showIndeterminate()
                UpdateInstaller.install(this@UpdateActivity, apk)
                // o resto chega pelo UpdateInstallReceiver (installListener)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha a atualizar", e)
                showError(getString(R.string.update_error_download, e.message ?: "?"), retryInstall = true)
            }
        }
    }

    private fun openReleasePage(found: UpdateInfo?) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found?.pageUrl ?: UpdateChecker.RELEASES_PAGE)))
        } catch (e: ActivityNotFoundException) {
            status.setText(R.string.update_error_no_browser)
        }
    }

    // --- estado no ecrã ---

    private fun showError(message: String, retryInstall: Boolean) {
        hideProgress()
        status.text = message
        val found = info
        button.visibility = View.VISIBLE
        button.isEnabled = true
        button.setText(R.string.update_button_retry)
        button.setOnClickListener {
            if (retryInstall && found != null) onInstallClicked(found) else check()
        }
    }

    private fun showIndeterminate() {
        progress.visibility = View.GONE
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
    }

    private fun showPercent(pct: Int) {
        if (pct < 0) return
        if (progress.isIndeterminate) {
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            progress.max = 100
            progress.visibility = View.VISIBLE
        }
        progress.setProgressCompat(pct, true)
        status.text = getString(R.string.update_status_downloading_pct, pct)
    }

    private fun hideProgress() {
        progress.visibility = View.GONE
    }

    private companion object {
        const val TAG = "UpdateActivity"
    }
}
