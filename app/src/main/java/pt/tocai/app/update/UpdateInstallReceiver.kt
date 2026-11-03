package pt.tocai.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import pt.tocai.app.R

/** Recebe o estado da sessão do PackageInstaller (não exportado: só o sistema o chama via PendingIntent). */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    fail(context, context.getString(R.string.update_error_install, message ?: "?"))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha a pedir confirmação da instalação", e)
                    fail(context, context.getString(R.string.update_error_install, e.message ?: "?"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // normalmente a app é terminada pelo sistema logo a seguir
                UpdateInstaller.deliver(UpdateInstaller.Status.Success)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                fail(context, context.getString(R.string.update_error_aborted))
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                fail(context, context.getString(R.string.update_error_storage))
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                fail(context, context.getString(R.string.update_error_conflict))
            else -> fail(context, context.getString(R.string.update_error_install, message ?: "código $status"))
        }
    }

    private fun fail(context: Context, text: String) {
        if (!UpdateInstaller.deliver(UpdateInstaller.Status.Failure(text))) {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS = "pt.tocai.app.action.INSTALL_STATUS"
        private const val TAG = "UpdateInstallReceiver"
    }
}
