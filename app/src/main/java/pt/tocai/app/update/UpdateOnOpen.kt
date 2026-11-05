package pt.tocai.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import pt.tocai.app.BuildConfig

/**
 * Verificação ao abrir a app: consulta o GitHub no máximo uma vez a cada 10 minutos
 * ([UpdatePromptPolicy.CHECK_INTERVAL_MS]) e guarda o resultado em SharedPreferences, para que a
 * pergunta possa aparecer em cada abertura sem repetir pedidos à API.
 */
object UpdateOnOpen {

    private const val TAG = "UpdateOnOpen"
    private const val PREFS = "tocai_update_open"
    private const val K_LAST_CHECK = "last_check_at"
    private const val K_CODE = "cached_code"
    private const val K_NAME = "cached_name"
    private const val K_NOTES = "cached_notes"
    private const val K_APK = "cached_apk"
    private const val K_PAGE = "cached_page"

    /**
     * Versão nova (da cache ou do GitHub), ou null. Nunca lança (exceto cancelamento): sem Internet
     * ou erro da API devolve null e esquece a cache, para não perguntar.
     * Chamar fora da thread principal.
     */
    suspend fun latest(ctx: Context, now: Long = System.currentTimeMillis()): UpdateInfo? {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        if (!UpdatePromptPolicy.shouldCheck(now, prefs.getLong(K_LAST_CHECK, 0L))) {
            return readCache(prefs.getInt(K_CODE, 0), prefs.getString(K_NAME, null), prefs.getString(K_NOTES, null),
                prefs.getString(K_APK, null), prefs.getString(K_PAGE, null))
                ?.takeIf { UpdateChecker.isNewer(it.versionCode, current) }
        }
        val found = try {
            UpdateChecker.check(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Sem resposta do GitHub ao abrir: ${e.message}")
            prefs.edit().putLong(K_LAST_CHECK, now).remove(K_CODE).apply()
            return null
        }
        val edit = prefs.edit().putLong(K_LAST_CHECK, now)
        if (found == null) {
            edit.remove(K_CODE)
        } else {
            edit.putInt(K_CODE, found.versionCode)
                .putString(K_NAME, found.versionName)
                .putString(K_NOTES, found.notes)
                .putString(K_APK, found.apkUrl)
                .putString(K_PAGE, found.pageUrl)
        }
        edit.apply()
        return found
    }

    private fun readCache(code: Int, name: String?, notes: String?, apk: String?, page: String?): UpdateInfo? {
        if (code <= 0 || name.isNullOrBlank() || apk.isNullOrBlank()) return null
        return UpdateInfo(code, name, notes.orEmpty(), apk, page?.ifBlank { null } ?: UpdateChecker.RELEASES_PAGE)
    }
}
