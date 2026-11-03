package pt.tocai.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import pt.tocai.app.R
import pt.tocai.app.data.CalendarEvent
import pt.tocai.app.util.TimeFormat

/** Lista de eventos agrupada por dia, com interruptor de despertador e botão "Relógio". */
class EventsAdapter(
    private val onToggle: (Row, Boolean) -> Unit,
    private val onClock: (Row) -> Unit,
) : RecyclerView.Adapter<EventsAdapter.VH>() {

    /** Um despertador de um evento: hora a que toca e minutos de antecedência. */
    data class AlarmTime(val triggerAt: Long, val offsetMinutes: Int)

    /** Linha já calculada (fora da thread principal). */
    data class Row(
        val event: CalendarEvent,
        val autoEnabled: Boolean,
        val enabled: Boolean,
        val forcedOn: Boolean,
        val forcedOff: Boolean,
        val alarms: List<AlarmTime>,   // ordenados por triggerAt
        val dayHeader: String?,        // null = não mostra cabeçalho
    ) {
        fun futureAlarms(now: Long): List<AlarmTime> = alarms.filter { it.triggerAt > now }
    }

    private var items: List<Row> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submit(rows: List<Row>) {
        items = rows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].event.key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.main_item_event, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val day: TextView = v.findViewById(R.id.main_item_day)
        private val color: View = v.findViewById(R.id.main_item_color)
        private val time: TextView = v.findViewById(R.id.main_item_time)
        private val title: TextView = v.findViewById(R.id.main_item_title)
        private val meta: TextView = v.findViewById(R.id.main_item_meta)
        private val chips: ChipGroup = v.findViewById(R.id.main_item_chips)
        private val note: TextView = v.findViewById(R.id.main_item_note)
        private val sw: MaterialSwitch = v.findViewById(R.id.main_item_switch)
        private val clock: MaterialButton = v.findViewById(R.id.main_item_clock)

        fun bind(row: Row) {
            val ctx = itemView.context
            val ev = row.event
            val now = System.currentTimeMillis()

            // Cabeçalho do dia
            if (row.dayHeader != null) {
                day.visibility = View.VISIBLE
                day.text = row.dayHeader
            } else {
                day.visibility = View.GONE
            }

            // Cor do calendário (algumas contas devolvem alfa 0)
            color.backgroundTintList = ColorStateList.valueOf(ev.color or OPAQUE)

            time.text = if (ev.allDay) {
                ctx.getString(R.string.main_all_day)
            } else {
                ctx.getString(R.string.main_time_range, TimeFormat.hm(ev.begin), TimeFormat.hm(ev.end))
            }
            title.text = ev.title

            val sep = ctx.getString(R.string.main_meta_separator)
            val metaParts = ArrayList<String>(4)
            if (ev.calendarName.isNotBlank()) metaParts.add(ev.calendarName)
            if (ev.location.isNotBlank()) metaParts.add(ev.location)
            if (ev.declined) metaParts.add(ctx.getString(R.string.main_declined))
            if (row.forcedOn) metaParts.add(ctx.getString(R.string.main_forced_on))
            if (row.forcedOff) metaParts.add(ctx.getString(R.string.main_forced_off))
            meta.text = metaParts.joinToString(sep)
            meta.visibility = if (metaParts.isEmpty()) View.GONE else View.VISIBLE

            val future = row.futureAlarms(now)
            val allPast = row.alarms.isNotEmpty() && future.isEmpty()

            // Chips com as horas dos despertadores
            chips.removeAllViews()
            note.visibility = View.GONE
            when {
                ev.allDay -> showNote(ctx.getString(R.string.main_all_day_no_alarm))
                !row.enabled -> showNote(ctx.getString(R.string.main_alarm_off))
                row.alarms.isEmpty() -> showNote(ctx.getString(R.string.main_alarm_off))
                else -> {
                    for (a in row.alarms) chips.addView(makeChip(a, a.triggerAt <= now))
                }
            }
            chips.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE

            // Interruptor: sem disparar o listener durante o bind
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = row.enabled && !ev.allDay
            sw.isEnabled = !ev.allDay && !allPast && ev.begin > now
            sw.setOnCheckedChangeListener { _, checked ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) onToggle(items[pos], checked)
            }

            // Botão Relógio: só faz sentido com um despertador por tocar
            val clockOk = !ev.allDay && future.isNotEmpty()
            clock.isEnabled = clockOk
            clock.alpha = if (clockOk) 1f else 0.38f
            clock.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) onClock(items[pos])
            }

            itemView.alpha = if (ev.begin <= now || ev.declined) 0.7f else 1f
        }

        private fun showNote(text: String) {
            note.text = text
            note.visibility = View.VISIBLE
        }

        private fun makeChip(a: AlarmTime, past: Boolean): Chip {
            val ctx = itemView.context
            val label = when {
                past -> ctx.getString(R.string.main_alarm_chip, TimeFormat.hm(a.triggerAt),
                    ctx.getString(R.string.main_alarm_passed))
                a.offsetMinutes == 0 -> ctx.getString(R.string.main_alarm_chip_at_start, TimeFormat.hm(a.triggerAt))
                else -> ctx.getString(R.string.main_alarm_chip, TimeFormat.hm(a.triggerAt),
                    TimeFormat.minutesLabel(a.offsetMinutes))
            }
            return Chip(ctx).apply {
                text = label
                isCheckable = false
                isClickable = false
                isFocusable = false
                setEnsureMinTouchTargetSize(false)
                isEnabled = !past
            }
        }
    }

    private companion object {
        const val OPAQUE: Int = -0x1000000 // 0xFF000000
    }
}
