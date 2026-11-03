package pt.tocai.app.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import pt.tocai.app.R
import pt.tocai.app.data.CalendarInfo
import pt.tocai.app.data.CalendarRepository
import pt.tocai.app.data.SettingsStore
import pt.tocai.app.sync.SyncWorker
import kotlin.math.roundToInt

/** Escolha dos calendários automáticos e, opcionalmente, dos minutos antes de cada um. */
object CalendarPickerDialog {

    private const val TAG = "CalendarPickerDialog"

    private class Row(
        val info: CalendarInfo,
        val check: MaterialCheckBox,
        val offsetsLayout: TextInputLayout,
        val offsetsInput: TextInputEditText,
    )

    fun show(activity: AppCompatActivity, onSaved: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (!CalendarRepository.hasPermission(activity)) {
            message(activity, R.string.settings_picker_no_permission)
            return
        }
        val calendars = try {
            CalendarRepository.calendars(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a ler calendários", e)
            emptyList()
        }
        if (calendars.isEmpty()) {
            message(activity, R.string.settings_picker_no_calendars)
            return
        }

        val store = SettingsStore(activity)
        val selected = store.autoCalendarIds
        val defaultLabel = SettingsFragment.offsetsLabel(activity, store.defaultOffsets)

        val builder = MaterialAlertDialogBuilder(activity)
        val ctx = builder.context
        val rows = mutableListOf<Row>()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 4), dp(ctx, 24), dp(ctx, 8))
        }
        content.addView(TextView(ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            text = ctx.getString(R.string.settings_picker_intro)
            setPadding(0, 0, 0, dp(ctx, 8))
        })

        // agrupados por conta, com a cor de cada calendário
        val localLabel = ctx.getString(R.string.settings_picker_local_account)
        val groups = calendars
            .groupBy { it.account.trim().ifEmpty { localLabel } }
            .entries
            .sortedBy { it.key.lowercase() }
        val headerColor = MaterialColors.getColor(
            ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF1A73E8.toInt(),
        )

        for ((account, list) in groups) {
            content.addView(TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(headerColor)
                text = account
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
            })
            for (info in list) {
                rows += addCalendarRow(ctx, content, info, info.id in selected, store, defaultLabel)
            }
        }

        val scroll = ScrollView(ctx).apply {
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        val dialog = builder
            .setTitle(R.string.settings_picker_title)
            .setView(scroll)
            .setPositiveButton(R.string.settings_picker_save, null)
            .setNegativeButton(R.string.settings_picker_cancel, null)
            .create()

        // botão "Guardar" próprio: só fecha se os minutos forem válidos
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                if (save(activity, store, rows)) {
                    dialog.dismiss()
                    onSaved()
                }
            }
        }
        dialog.show()
    }

    private fun addCalendarRow(
        ctx: Context,
        parent: LinearLayout,
        info: CalendarInfo,
        checked: Boolean,
        store: SettingsStore,
        defaultLabel: String,
    ): Row {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotSize = dp(ctx, 14)
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(info.color)
            }
        }
        line.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = dp(ctx, 4)
        })

        val check = MaterialCheckBox(ctx).apply {
            text = info.name
            isChecked = checked
        }
        line.addView(
            check,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        parent.addView(line)

        val input = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            keyListener = DigitsKeyListener.getInstance("0123456789, ")
            setText(store.calendarOffsets(info.id)?.joinToString(",").orEmpty())
        }
        val layout = TextInputLayout(ctx).apply {
            hint = ctx.getString(R.string.settings_picker_offsets_hint)
            helperText = ctx.getString(R.string.settings_picker_offsets_helper, defaultLabel)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            isVisible = checked
        }
        parent.addView(
            layout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(ctx, 18)
                bottomMargin = dp(ctx, 8)
            },
        )

        check.setOnCheckedChangeListener { _, isChecked ->
            layout.isVisible = isChecked
            if (!isChecked) layout.error = null
        }
        return Row(info, check, layout, input)
    }

    /** Valida e grava. Devolve false se algum campo tiver minutos inválidos. */
    private fun save(activity: AppCompatActivity, store: SettingsStore, rows: List<Row>): Boolean {
        var valid = true
        val parsed = HashMap<Long, List<Int>?>()
        for (row in rows) {
            row.offsetsLayout.error = null
            if (!row.check.isChecked) continue
            val text = row.offsetsInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                parsed[row.info.id] = null
            } else {
                val list = SettingsStore.parseOffsets(text)
                if (list.isEmpty()) {
                    row.offsetsLayout.error = activity.getString(R.string.settings_picker_offsets_invalid)
                    valid = false
                } else {
                    parsed[row.info.id] = list
                }
            }
        }
        if (!valid) return false

        // calendários desmarcados mantêm os minutos, para o caso de voltarem a ser escolhidos
        for ((id, offsets) in parsed) store.setCalendarOffsets(id, offsets)
        store.autoCalendarIds = rows.filter { it.check.isChecked }.map { it.info.id }.toSet()
        try {
            SyncWorker.runNow(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a pedir sincronização", e)
        }
        return true
    }

    private fun message(activity: AppCompatActivity, textRes: Int) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_picker_title)
            .setMessage(textRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
}
