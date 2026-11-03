package pt.tocai.app.ui

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import pt.tocai.app.R
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.rules.AlarmPlanner
import pt.tocai.app.util.TimeFormat
import kotlin.math.roundToInt

/** "Quanto tempo antes?" para um evento: guarda em SettingsStore.setEventOffsets (null = como os outros). */
object OffsetsDialog {

    private val PRESETS: List<List<Int>> = listOf(
        listOf(0), listOf(5), listOf(10), listOf(15), listOf(30), listOf(60), listOf(15, 5),
    )

    /** "15 min e 5 min" / "à hora". */
    fun label(ctx: Context, offsets: List<Int>): String {
        val parts = offsets.map { if (it == 0) ctx.getString(R.string.offsets_at_start) else TimeFormat.minutesLabel(it) }
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + ctx.getString(R.string.offsets_and) + parts.last()
        }
    }

    fun show(activity: AppCompatActivity, event: CalendarEvent, onSaved: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val store = SettingsStore(activity)
        val rs = store.toRuleSettings()
        // o que o evento teria sem minutos próprios (calendário ou defeito)
        val base = AlarmPlanner.offsetsFor(event, rs.copy(eventOffsets = rs.eventOffsets - event.key))
        val current = store.eventOffsets(event.key)

        val labels = ArrayList<String>()
        labels += activity.getString(R.string.offsets_option_default, label(activity, base))
        for (p in PRESETS) {
            labels += if (p == listOf(0)) {
                activity.getString(R.string.offsets_option_at_start)
            } else {
                activity.getString(R.string.offsets_option_before, label(activity, p))
            }
        }
        val presetIndex = current?.let { c -> PRESETS.indexOfFirst { it.sortedDescending() == c } } ?: -1
        val customIndex = PRESETS.size + 1
        labels += if (current != null && presetIndex < 0) {
            activity.getString(R.string.offsets_option_custom_current, label(activity, current))
        } else {
            activity.getString(R.string.offsets_option_custom)
        }
        val checked = when {
            current == null -> 0
            presetIndex >= 0 -> presetIndex + 1
            else -> customIndex
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offsets_dialog_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> save(activity, event, null, onSaved)
                    customIndex -> showCustom(activity, event, current ?: base, onSaved)
                    else -> save(activity, event, PRESETS[which - 1], onSaved)
                }
            }
            .setNegativeButton(R.string.offsets_cancel, null)
            .show()
    }

    private fun showCustom(activity: AppCompatActivity, event: CalendarEvent, initial: List<Int>, onSaved: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val builder = MaterialAlertDialogBuilder(activity)
        val ctx = builder.context
        val input = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            keyListener = DigitsKeyListener.getInstance("0123456789, ")
            setText(initial.joinToString(","))
            setSelection(text?.length ?: 0)
        }
        val layout = TextInputLayout(ctx).apply {
            hint = ctx.getString(R.string.offsets_custom_hint)
            addView(input)
        }
        val pad = (24 * ctx.resources.displayMetrics.density).roundToInt()
        val frame = FrameLayout(ctx).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(layout)
        }
        val dialog = builder
            .setTitle(R.string.offsets_custom_title)
            .setView(frame)
            .setPositiveButton(R.string.offsets_save, null)
            .setNegativeButton(R.string.offsets_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val parsed = SettingsStore.parseOffsets(input.text?.toString())
                if (parsed.isEmpty()) {
                    layout.error = ctx.getString(R.string.offsets_custom_invalid)
                } else {
                    dialog.dismiss()
                    save(activity, event, parsed, onSaved)
                }
            }
        }
        dialog.show()
    }

    private fun save(activity: AppCompatActivity, event: CalendarEvent, offsets: List<Int>?, onSaved: () -> Unit) {
        try {
            SettingsStore(activity).setEventOffsets(event.key, offsets)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.offsets_custom_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        onSaved()
    }
}
