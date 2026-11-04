package pt.tocai.app.util

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Deteta se o utilizador está numa chamada (telefone) ou numa reunião/chamada de uma app de
 * comunicação (Teams, WhatsApp, Zoom, Meet…), sem permissões novas: usa só o AudioManager.mode.
 */
object CallState {

    private const val TAG = "CallState"

    // Valores de AudioManager.MODE_* (fixos na API pública; repetidos aqui para os testes JVM)
    const val MODE_NORMAL = 0
    const val MODE_RINGTONE = 1                 // a tocar uma chamada recebida
    const val MODE_IN_CALL = 2                  // chamada telefónica
    const val MODE_IN_COMMUNICATION = 3         // VoIP / reunião (Teams, WhatsApp, Zoom, Meet…)
    const val MODE_CALL_SCREENING = 4           // API 30+
    const val MODE_CALL_REDIRECT = 5            // API 33+
    const val MODE_COMMUNICATION_REDIRECT = 6   // API 33+

    /** Lógica pura: o [mode] do AudioManager indica chamada/reunião nesta versão [sdkInt] do Android? */
    fun isCallMode(mode: Int, sdkInt: Int): Boolean = when (mode) {
        MODE_RINGTONE, MODE_IN_CALL, MODE_IN_COMMUNICATION -> true
        MODE_CALL_SCREENING -> sdkInt >= 30   // Build.VERSION_CODES.R
        MODE_CALL_REDIRECT, MODE_COMMUNICATION_REDIRECT -> sdkInt >= 33   // Build.VERSION_CODES.TIRAMISU
        else -> false
    }

    /** Wrapper Android: lê o modo de áudio atual. Em caso de erro assume que não há chamada. */
    fun isInCall(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am != null && isCallMode(am.mode, Build.VERSION.SDK_INT)
    } catch (e: Exception) {
        Log.w(TAG, "Falha a ler o modo de áudio", e)
        false
    }
}
