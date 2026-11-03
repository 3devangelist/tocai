package pt.ricardo.caldespertador

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val pt = Locale("pt", "PT")
    private val hm = SimpleDateFormat("HH:mm", pt)
    private val dayFmt = SimpleDateFormat("EEEE, d 'de' MMMM", pt)

    private lateinit var prefs: Prefs
    private val adapter = EventsAdapter()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        AlarmService.createChannel(this)

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<Button>(R.id.btnMinus).setOnClickListener { changeMinutes(-step()) }
        findViewById<Button>(R.id.btnPlus).setOnClickListener { changeMinutes(+step()) }
        findViewById<Button>(R.id.btnCalendars).setOnClickListener { pickCalendars() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testAlarm() }

        val missing = buildList {
            if (!CalendarRepo.hasPermission(this@MainActivity)) add(Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun step() = if (prefs.minutesBefore >= 15) 5 else 1

    private fun changeMinutes(delta: Int) {
        prefs.minutesBefore = prefs.minutesBefore + delta
        refresh()
    }

    private fun refresh() {
        val hasCal = CalendarRepo.hasPermission(this)
        if (hasCal && !prefs.firstRunDone) {
            prefs.firstRunDone = true
            // Por defeito, liga os calendários de consultorias
            prefs.autoCalendars = CalendarRepo.calendars(this)
                .filter { it.name.contains("consult", ignoreCase = true) }
                .map { it.id }.toSet()
            pickCalendars()
        }

        AlarmScheduler.sync(this)
        SyncWorker.scheduleAll(this)

        findViewById<TextView>(R.id.txtMinutes).text = "${prefs.minutesBefore} min"
        val cals = CalendarRepo.calendars(this)
        val autoNames = cals.filter { it.id in prefs.autoCalendars }.map { it.name }
        findViewById<TextView>(R.id.txtCalendars).text =
            if (autoNames.isEmpty()) "Nenhum calendário automático. Liga eventos um a um na lista."
            else "Despertador automático em: " + autoNames.joinToString(", ")

        val next = AlarmScheduler.nextScheduled(this)
        findViewById<TextView>(R.id.txtNext).text = next?.let { (at, ev) ->
            "Próximo despertador: ${relDay(at)} às ${hm.format(Date(at))} — ${ev.title}"
        } ?: "Nenhum despertador agendado."

        val events = CalendarRepo.upcoming(this)
        adapter.items = events
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.txtEmpty).visibility =
            if (hasCal && events.isEmpty()) View.VISIBLE else View.GONE

        updateWarning()
    }

    private fun updateWarning() {
        val box = findViewById<View>(R.id.warnBox)
        val txt = findViewById<TextView>(R.id.warnText)
        val btn = findViewById<Button>(R.id.warnBtn)
        val nm = getSystemService(NotificationManager::class.java)

        val (msg, action) = when {
            !CalendarRepo.hasPermission(this) ->
                "A app precisa de acesso ao calendário." to { openAppSettings() }
            !AlarmScheduler.canScheduleExact(this) ->
                "Falta a permissão \"Alarmes e lembretes\"." to {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            !nm.areNotificationsEnabled() ->
                "As notificações estão desligadas: sem elas o despertador não aparece." to { openNotifSettings() }
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                "Falta permitir \"ecrã inteiro\" para o despertador aparecer com o telefone bloqueado." to {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
            else -> null to null
        }
        box.visibility = if (msg == null) View.GONE else View.VISIBLE
        txt.text = msg
        btn.setOnClickListener { action?.invoke() }
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun openNotifSettings() =
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

    private fun pickCalendars() {
        val cals = CalendarRepo.calendars(this)
        if (cals.isEmpty()) {
            Toast.makeText(this, "Nenhum calendário encontrado no telefone.", Toast.LENGTH_LONG).show()
            return
        }
        val selected = prefs.autoCalendars.toMutableSet()
        val labels = cals.map { if (it.account.isNotBlank() && it.account != it.name) "${it.name}\n${it.account}" else it.name }
        val checked = cals.map { it.id in selected }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Que calendários ganham despertador automático?")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) selected += cals[which].id else selected -= cals[which].id
            }
            .setPositiveButton("Guardar") { _, _ ->
                prefs.autoCalendars = selected
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testAlarm() {
        if (!AlarmScheduler.canScheduleExact(this)) { updateWarning(); return }
        val now = System.currentTimeMillis()
        AlarmScheduler.schedule(this, "teste", now + 10_000, "Teste do despertador",
            now + 10_000 + prefs.minutesBefore * 60_000L, "Se estás a ver isto, está tudo a funcionar")
        Toast.makeText(this, "Vai tocar daqui a 10 segundos. Podes bloquear o ecrã.", Toast.LENGTH_LONG).show()
    }

    /** Alternativa: cria o alarme na app Relógio do telefone. */
    private fun sendToClockApp(ev: CalEvent) {
        val at = AlarmScheduler.triggerTime(this, ev)
        if (at - System.currentTimeMillis() > 24 * 3600_000L) {
            Toast.makeText(this, "A app Relógio só aceita alarmes para as próximas 24 horas.", Toast.LENGTH_LONG).show()
            return
        }
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_MESSAGE, ev.title.take(60))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        try {
            startActivity(i)
            Toast.makeText(this, "Alarme das ${hm.format(Date(at))} criado na app Relógio.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Não encontrei nenhuma app Relógio.", Toast.LENGTH_LONG).show()
        }
    }

    private fun relDay(t: Long): String {
        val a = Calendar.getInstance().apply { timeInMillis = t }
        val n = Calendar.getInstance()
        val diff = (a.get(Calendar.YEAR) * 400 + a.get(Calendar.DAY_OF_YEAR)) -
            (n.get(Calendar.YEAR) * 400 + n.get(Calendar.DAY_OF_YEAR))
        return when (diff) {
            0 -> "hoje"
            1 -> "amanhã"
            else -> dayFmt.format(Date(t))
        }
    }

    private fun dayKey(t: Long) = Calendar.getInstance().run {
        timeInMillis = t; get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
    }

    inner class EventsAdapter : RecyclerView.Adapter<EventsAdapter.VH>() {
        var items: List<CalEvent> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val day: TextView = v.findViewById(R.id.txtDay)
            val bar: View = v.findViewById(R.id.colorBar)
            val time: TextView = v.findViewById(R.id.txtTime)
            val title: TextView = v.findViewById(R.id.txtTitle)
            val sub: TextView = v.findViewById(R.id.txtSub)
            val sw: MaterialSwitch = v.findViewById(R.id.sw)
            val clock: ImageButton = v.findViewById(R.id.btnClock)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ev = items[pos]
            val showDay = pos == 0 || dayKey(items[pos - 1].begin) != dayKey(ev.begin)
            h.day.visibility = if (showDay) View.VISIBLE else View.GONE
            h.day.text = relDay(ev.begin).replaceFirstChar { it.uppercase() }
            h.bar.setBackgroundColor(ev.color or 0xFF000000.toInt())
            h.time.text = "${hm.format(Date(ev.begin))} – ${hm.format(Date(ev.end))}"
            h.title.text = ev.title
            val at = AlarmScheduler.triggerTime(this@MainActivity, ev)
            val past = at <= System.currentTimeMillis()
            val on = prefs.isEnabled(ev)
            h.sub.text = ev.calendarName + when {
                past -> " · já passou"
                on -> " · ⏰ ${hm.format(Date(at))}"
                else -> ""
            }
            h.clock.imageTintList = ColorStateList.valueOf(0xFF1A73E8.toInt())
            h.clock.setOnClickListener { sendToClockApp(ev) }
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = on
            h.sw.isEnabled = !past
            h.sw.setOnCheckedChangeListener { _, checked ->
                prefs.setEnabled(ev, checked)
                refresh()
            }
        }
    }
}
