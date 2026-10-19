package se.jptv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import se.jptv.BuildConfig
import java.io.File
import java.io.IOException

data class AppRelease(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Delat läge mellan uppdateraren och installationsmottagaren (som körs utanför Compose). */
object UpdateState {
    var message by mutableStateOf<String?>(null)
}

/**
 * Kollar GitHub efter en nyare JPTV, laddar ner APK:n och lämnar över till Androids
 * installerare. Android visar en bekräftelse som användaren godkänner med OK.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    private val versionRegex = Regex("""v?\d+\.\d+\.(\d+)""")

    /** Senaste releasen om den är nyare än den installerade, annars null. */
    suspend fun checkForUpdate(): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "JPTV/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        val json = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub svarade HTTP ${resp.code}")
            JSONObject(resp.body?.string().orEmpty())
        }
        val tag = json.optString("tag_name")
        val code = versionRegex.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK_NAME) apkUrl = a.optString("browser_download_url")
        }
        if (apkUrl.isNullOrBlank() || code <= BuildConfig.VERSION_CODE) return@withContext null
        AppRelease(code, tag.removePrefix("v"), apkUrl)
    }

    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "jptv-${release.versionCode}.apk")
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", "JPTV").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Nedladdningen svarade HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Tom nedladdning")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        target
    }

    /** Får appen installera uppdateringar? Första gången måste det tillåtas i TV:ns inställningar. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Öppnar inställningen "Installera okända appar" för JPTV, om TV:n har den skärmen. */
    fun openInstallPermissionSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Skickar APK:n till Androids installerare. Resultatet kommer till [InstallResultReceiver]. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: uppdatera utan fråga när det går. Äldre (t.ex. Shield) frågar alltid en gång.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("jptv.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        const val APK_NAME = "jptv.apk"
    }
}
