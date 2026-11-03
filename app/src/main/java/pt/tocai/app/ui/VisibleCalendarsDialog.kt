package pt.tocai.app.ui

import android.content.Context
import android.content.DialogInterface
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pt.tocai.app.R
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.SettingsStore

/**
 * "Calendários visíveis": escolhe que calendários aparecem na lista e na escolha da manhã.
 * Só afeta a interface: um evento já escolhido continua a tocar mesmo que o calendário fique escondido.
 */
object VisibleCalendarsDialog {

    private const val TAG = "VisibleCalendarsDialog"

    fun show(activity: AppCompatActivity, onSaved: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!CalendarRepository.hasPermission(activity)) {
            toast(activity, R.string.visible_no_permission)
            return
        }
        val calendars = try {
            CalendarRepository.calendars(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a ler calendários", e)
            emptyList()
        }
        if (calendars.isEmpty()) {
            toast(activity, R.string.visible_no_calendars)
            return
        }
        val store = SettingsStore(activity)
        val hidden = store.hiddenCalendarIds
        val labels = calendars.map { c ->
            if (c.account.isBlank() || c.account == c.name) c.name else "${c.name} · ${c.account}"
        }.toTypedArray()
        val checked = BooleanArray(calendars.size) { calendars[it].id !in hidden }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.visible_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.visible_save, null)
            .setNegativeButton(R.string.visible_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                if (checked.none { it }) {
                    toast(activity, R.string.visible_none_selected)
                    return@setOnClickListener
                }
                val presentIds = calendars.map { it.id }.toSet()
                // mantém escondidos os calendários que agora não aparecem (ex.: conta removida temporariamente)
                val newHidden = (hidden - presentIds) +
                    calendars.filterIndexed { i, _ -> !checked[i] }.map { it.id }
                store.hiddenCalendarIds = newHidden
                dialog.dismiss()
                onSaved()
            }
        }
        dialog.show()
    }

    /** Resumo para as Definições. */
    fun summary(ctx: Context): String {
        val n = SettingsStore(ctx).hiddenCalendarIds.size
        return if (n == 0) ctx.getString(R.string.visible_summary_all)
        else ctx.resources.getQuantityString(R.plurals.visible_summary_hidden, n, n)
    }

    private fun toast(ctx: Context, res: Int) {
        Toast.makeText(ctx, res, Toast.LENGTH_LONG).show()
    }
}
