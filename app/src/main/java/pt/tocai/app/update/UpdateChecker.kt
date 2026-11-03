package pt.tocai.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pt.tocai.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Uma versão nova publicada nas Releases do GitHub. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

/**
 * Procura versões novas nas Releases do GitHub (repositório público).
 * Não envia nada sobre o utilizador nem sobre o calendário: é só um GET anónimo.
 */
object UpdateChecker {

    const val REPO = "3devangelist/tocai"
    const val APK_NAME = "Tocai.apk"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TIMEOUT_MS = 15_000

    /** Versão nova, ou null se já está na mais recente. Lança IOException em falhas de rede ou do servidor. */
    @Throws(IOException::class)
    suspend fun check(currentCode: Int = BuildConfig.VERSION_CODE): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val info = parseRelease(fetchLatest())
                ?: throw IOException("A versão publicada não tem o ficheiro $APK_NAME")
            if (isNewer(info.versionCode, currentCode)) info else null
        }

    /** Como [check], mas devolve null também em caso de erro. */
    suspend fun checkQuietly(currentCode: Int = BuildConfig.VERSION_CODE): UpdateInfo? =
        try { check(currentCode) } catch (e: Exception) { null }

    private fun fetchLatest(): String {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Tocai")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw IOException("Ainda não há versões publicadas")
            if (code !in 200..299) throw IOException("O GitHub respondeu com o código $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Lê a resposta de /releases/latest. null se não tiver uma tag válida ou o APK. */
    fun parseRelease(json: String): UpdateInfo? {
        val o = try { JSONObject(json) } catch (e: Exception) { return null }
        val tag = o.optString("tag_name", "")
        val code = parseVersionCode(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val u = a.optString("browser_download_url", "")
            if (u.isBlank()) continue
            if (name == APK_NAME) {
                url = u
                break
            }
            if (url == null && name.endsWith(".apk", ignoreCase = true)) url = u
        }
        val apk = url ?: return null
        return UpdateInfo(
            versionCode = code,
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            notes = o.optString("body", "").trim(),
            apkUrl = apk,
            pageUrl = o.optString("html_url", "").ifBlank { RELEASES_PAGE },
        )
    }

    /** "v1.0.42" -> 42 (o último número da tag é o versionCode). Inválida -> null. */
    fun parseVersionCode(tag: String?): Int? {
        val t = tag?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (t.isEmpty()) return null
        val parts = t.split('.')
        if (parts.any { p -> p.isEmpty() || !p.all { it.isDigit() } }) return null
        return parts.last().toIntOrNull()?.takeIf { it > 0 }
    }

    fun isNewer(remoteCode: Int, currentCode: Int): Boolean = remoteCode > currentCode

    fun isNewer(tag: String?, currentCode: Int): Boolean =
        parseVersionCode(tag)?.let { isNewer(it, currentCode) } ?: false
}
