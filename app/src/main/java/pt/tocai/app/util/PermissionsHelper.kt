package pt.tocai.app.util

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pt.tocai.app.R
import pt.tocai.app.alarm.AlarmScheduler
import pt.tocai.app.data.CalendarRepository

/** Verificação das permissões e acessos especiais de que o Tocaí precisa. */
object PermissionsHelper {

    private const val TAG = "PermissionsHelper"

    data class Problem(
        val message: String,
        val action: String,
        val intent: Intent?,
        val runtimePermissions: Array<String>,
    )

    /** Permissões pedidas em tempo de execução (as que se aplicam a esta versão do Android). */
    fun runtimePermissionsNeeded(ctx: Context): Array<String> {
        val list = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun isGranted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun hasCalendar(ctx: Context): Boolean =
        try {
            CalendarRepository.hasPermission(ctx)
        } catch (e: Exception) {
            isGranted(ctx, Manifest.permission.READ_CALENDAR)
        }

    /** Permissão de notificações (33+) concedida e notificações da app ligadas. */
    fun notificationsEnabled(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        ) return false
        return try {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        } catch (e: Exception) {
            true
        }
    }

    fun canScheduleExact(ctx: Context): Boolean =
        try {
            AlarmScheduler.canScheduleExact(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "canScheduleExact falhou", e)
            false
        }

    /** Ecrã inteiro só precisa de autorização especial a partir da API 34. */
    fun canUseFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
        return try {
            nm.canUseFullScreenIntent()
        } catch (e: Exception) {
            true
        }
    }

    /**
     * "Mostrar por cima de outras apps" (SYSTEM_ALERT_WINDOW). Com ela, o Android deixa o Tocaí abrir
     * atividades a partir de segundo plano (ex.: o ecrã do despertador com o telemóvel em uso).
     */
    fun canDrawOverlays(ctx: Context): Boolean =
        try {
            Settings.canDrawOverlays(ctx)
        } catch (e: Exception) {
            false
        }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return try {
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            true
        }
    }

    private fun packageUri(ctx: Context): Uri = Uri.parse("package:" + ctx.packageName)

    fun exactAlarmIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(ctx))
        } else {
            appSettingsIntent(ctx)
        }

    fun fullScreenIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(ctx))
        } else {
            notificationSettingsIntent(ctx)
        }

    fun overlayIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(ctx))

    /** Lista geral de "Mostrar por cima de outras apps" (alternativa sem o pacote). */
    fun overlayListIntent(): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

    /**
     * Abre uma atividade a partir de segundo plano (serviço/receiver) só quando o Android o permite,
     * isto é, com "Mostrar por cima de outras apps". Devolve true se a abriu. Nunca lança exceções.
     */
    fun startActivityFromBackground(ctx: Context, intent: Intent): Boolean {
        if (!canDrawOverlays(ctx)) return false
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Falha a abrir ${intent.component?.className} em segundo plano", e)
            false
        }
    }

    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(ctx))

    /** Lista geral de otimização da bateria (alternativa quando o pedido direto não existe). */
    fun batteryListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(ctx))

    fun notificationSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    /**
     * Abre o primeiro intent que o telemóvel souber tratar; no fim tenta os detalhes da app.
     * Devolve false se nada abriu.
     */
    fun startFirstAvailable(ctx: Context, vararg intents: Intent): Boolean {
        val all = intents.toList() + appSettingsIntent(ctx)
        for (intent in all) {
            try {
                if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Sem atividade para ${intent.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Sem acesso a ${intent.action}", e)
            } catch (e: Exception) {
                Log.w(TAG, "Falha a abrir ${intent.action}", e)
            }
        }
        return false
    }

    /**
     * Primeiro problema por ordem: calendário, notificações, alarmes exatos, ecrã inteiro,
     * mostrar por cima de outras apps, bateria. null = tudo ok.
     */
    fun firstProblem(ctx: Context): Problem? {
        if (!hasCalendar(ctx)) {
            return Problem(
                message = ctx.getString(R.string.onboarding_problem_calendar),
                action = ctx.getString(R.string.onboarding_action_allow),
                intent = appSettingsIntent(ctx),
                runtimePermissions = arrayOf(Manifest.permission.READ_CALENDAR),
            )
        }
        if (!notificationsEnabled(ctx)) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)
            ) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            return Problem(
                message = ctx.getString(R.string.onboarding_problem_notifications),
                action = ctx.getString(R.string.onboarding_action_allow),
                intent = notificationSettingsIntent(ctx),
                runtimePermissions = perms,
            )
        }
        if (!canScheduleExact(ctx)) {
            return Problem(
                message = ctx.getString(R.string.onboarding_problem_exact),
                action = ctx.getString(R.string.onboarding_action_allow),
                intent = exactAlarmIntent(ctx),
                runtimePermissions = emptyArray(),
            )
        }
        if (!canUseFullScreen(ctx)) {
            return Problem(
                message = ctx.getString(R.string.onboarding_problem_fullscreen),
                action = ctx.getString(R.string.onboarding_action_allow),
                intent = fullScreenIntent(ctx),
                runtimePermissions = emptyArray(),
            )
        }
        if (!canDrawOverlays(ctx)) {
            return Problem(
                message = ctx.getString(R.string.onboarding_problem_overlay),
                action = ctx.getString(R.string.onboarding_action_allow),
                intent = overlayIntent(ctx),
                runtimePermissions = emptyArray(),
            )
        }
        if (!ignoringBatteryOptimizations(ctx)) {
            return Problem(
                message = ctx.getString(R.string.onboarding_problem_battery),
                action = ctx.getString(R.string.onboarding_action_battery),
                intent = batteryIntent(ctx),
                runtimePermissions = emptyArray(),
            )
        }
        return null
    }
}
