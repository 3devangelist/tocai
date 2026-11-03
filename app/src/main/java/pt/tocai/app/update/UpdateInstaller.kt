package pt.tocai.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Descarrega o APK novo e instala-o com o PackageInstaller do Android. */
object UpdateInstaller {

    /** Resultado final da instalação (entregue na thread principal). */
    sealed class Status {
        object Success : Status()
        data class Failure(val message: String) : Status()
    }

    private const val TIMEOUT_MS = 20_000
    private const val FILE_NAME = "Tocai-update.apk"
    private val main = Handler(Looper.getMainLooper())

    /** Quem quer saber como acabou a instalação (a UpdateActivity, enquanto está visível). */
    @Volatile
    var statusListener: ((Status) -> Unit)? = null

    internal fun deliver(status: Status): Boolean {
        val l = statusListener ?: return false
        main.post { l(status) }
        return true
    }

    /** O Android permite à app instalar pacotes? (definição "Instalar apps desconhecidas"). */
    fun canInstall(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        false
    }

    /** Abre a definição "Instalar apps desconhecidas" do Tocaí. */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))

    /**
     * Descarrega o APK para a cache. [onProgress] recebe 0..100, ou -1 se o tamanho for desconhecido
     * (é chamado na thread de IO).
     */
    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val out = File(ctx.cacheDir, FILE_NAME)
            out.delete()
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "Tocai")
                conn.setRequestProperty("Accept", "application/octet-stream")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("O download falhou (código $code)")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -2
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("O download ficou incompleto")
                if (done == 0L) throw IOException("O ficheiro descarregado está vazio")
                out
            } catch (e: Exception) {
                out.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Entrega o APK ao PackageInstaller. O Android pede depois confirmação ao utilizador
     * (STATUS_PENDING_USER_ACTION, tratado em [UpdateInstallReceiver]).
     */
    @Throws(IOException::class)
    suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext ?: ctx
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val intent = Intent(app, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                .setPackage(app.packageName)
            // tem de ser mutável: o sistema acrescenta o estado ao intent
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            try { session.abandon() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException(e.message ?: "Falha a preparar a instalação", e)
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }
}
