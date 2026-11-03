package pt.tocai.app.widget

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import pt.tocai.app.R
import pt.tocai.app.ui.MainActivity
import pt.tocai.app.util.TimeFormat

/** Tile das definições rápidas com o próximo despertador. */
class NextAlarmTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var listening = false

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        val appCtx = applicationContext
        // Leitura do calendário fora da thread principal
        Thread {
            val next = NextAlarmWidget.safeNextAlarm(appCtx)
            mainHandler.post { if (listening) applyState(next) }
        }.start()
    }

    override fun onStopListening() {
        listening = false
        super.onStopListening()
    }

    private fun applyState(next: pt.tocai.app.data.PlannedAlarm?) {
        val tile = qsTile ?: return
        try {
            if (next != null) {
                val time = TimeFormat.hm(next.triggerAt)
                val day = TimeFormat.relativeDay(next.triggerAt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.label = getString(R.string.widget_tile_label)
                    tile.subtitle = getString(R.string.widget_tile_subtitle, day, time)
                } else {
                    tile.label = getString(R.string.widget_tile_label_with_time, time)
                }
                tile.contentDescription = getString(
                    R.string.widget_tile_description, day, time,
                    next.event.title.ifBlank { getString(R.string.widget_untitled) }
                )
                tile.state = Tile.STATE_ACTIVE
            } else {
                tile.label = getString(R.string.widget_tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.widget_no_alarms)
                }
                tile.contentDescription = getString(R.string.widget_no_alarms)
                tile.state = Tile.STATE_INACTIVE
            }
            tile.updateTile()
        } catch (e: Exception) {
            Log.w("TocaiTile", "Falha a atualizar o tile", e)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(NextAlarmWidget.openAppPendingIntent(this))
            } else {
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w("TocaiTile", "Falha a abrir a app", e)
        }
    }
}
