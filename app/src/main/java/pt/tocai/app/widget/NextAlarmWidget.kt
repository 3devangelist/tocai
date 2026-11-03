package pt.tocai.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.RemoteViews
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.PlannedAlarm
import pt.tocai.app.ui.MainActivity
import pt.tocai.app.util.TimeFormat
import java.util.concurrent.Executors

/** Widget 2x1 com o próximo despertador. */
class NextAlarmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Ler o calendário pode demorar: faz-se fora da thread principal
        val pending = goAsync()
        val appCtx = context.applicationContext
        EXECUTOR.execute {
            try {
                render(appCtx, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TocaiWidget"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        /** Atualiza todos os widgets e o tile. Nunca lança exceções. */
        fun updateAll(ctx: Context) {
            val appCtx = ctx.applicationContext
            try {
                val mgr = AppWidgetManager.getInstance(appCtx)
                if (mgr != null) {
                    val ids = mgr.getAppWidgetIds(ComponentName(appCtx, NextAlarmWidget::class.java))
                    if (ids != null && ids.isNotEmpty()) {
                        EXECUTOR.execute { render(appCtx, mgr, ids) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha a atualizar o widget", e)
            }
            try {
                TileService.requestListeningState(appCtx, ComponentName(appCtx, NextAlarmTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Falha a pedir atualização do tile", e)
            }
        }

        /** Próximo despertador, ou null se não houver ou faltar permissão. */
        internal fun safeNextAlarm(ctx: Context): PlannedAlarm? = try {
            AlarmScheduler.nextAlarm(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "Sem acesso ao próximo despertador", e)
            null
        }

        internal fun openAppPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            try {
                val next = safeNextAlarm(ctx)
                val views = RemoteViews(ctx.packageName, R.layout.widget_next_alarm)
                if (next != null) {
                    views.setTextViewText(
                        R.id.widget_time,
                        ctx.getString(R.string.widget_time_format, TimeFormat.hm(next.triggerAt))
                    )
                    val title = next.event.title.ifBlank { ctx.getString(R.string.widget_untitled) }
                    views.setTextViewText(
                        R.id.widget_title,
                        ctx.getString(R.string.widget_title_format, TimeFormat.relativeDay(next.triggerAt), title)
                    )
                } else {
                    views.setTextViewText(R.id.widget_time, ctx.getString(R.string.widget_no_alarm_time))
                    views.setTextViewText(R.id.widget_title, ctx.getString(R.string.widget_no_alarms))
                }
                views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(ctx))
                mgr.updateAppWidget(ids, views)
            } catch (e: Exception) {
                Log.w(TAG, "Falha a desenhar o widget", e)
            }
        }
    }
}
