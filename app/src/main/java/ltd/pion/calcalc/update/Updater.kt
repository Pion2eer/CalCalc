package ltd.pion.calcalc.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ltd.pion.calcalc.BuildConfig
import ltd.pion.calcalc.data.Prefs
import ltd.pion.calcalc.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What `version.json` next to each release says. */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    data class ReadyToInstall(val release: Release, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

object Updater {

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val text = open(BuildConfig.UPDATE_URL).inputStream.bufferedReader().use { it.readText() }
        val j = JSONObject(text)
        Release(
            versionCode = j.getInt("versionCode"),
            versionName = j.getString("versionName"),
            apkUrl = j.getString("apkUrl"),
            sha256 = j.optString("sha256"),
            notes = j.optString("notes"),
        )
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    fun apkFile(context: Context, r: Release) = File(File(context.cacheDir, "updates").apply { mkdirs() }, "CalCalc-${r.versionCode}.apk")

    /** Downloads the APK into the cache and checks its SHA-256; returns the file ready for the installer. */
    suspend fun download(context: Context, r: Release, progress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val target = apkFile(context, r)
        if (target.exists() && (r.sha256.isEmpty() || sha256(target) == r.sha256.lowercase())) return@withContext target
        val part = File(target.path + ".part")
        val conn = open(r.apkUrl)
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        if (r.sha256.isNotEmpty() && sha256(part) != r.sha256.lowercase()) {
            part.delete()
            error("Файл обновления повреждён, попробуйте ещё раз")
        }
        target.delete()
        part.renameTo(target)
        // old downloads only take space
        target.parentFile?.listFiles()?.filter { it != target }?.forEach { it.delete() }
        target
    }

    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows CalCalc to install updates (asked once). */
    fun askInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to the system installer; Android shows one "Install" confirmation. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        // GitHub answers release downloads with redirects to another host, which HttpURLConnection does not follow across protocols
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "CalCalc/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(u, c.getHeaderField("Location"))
                c.disconnect()
            } else if (code == 200) {
                return c
            } else {
                c.disconnect()
                error("Сервер обновлений ответил $code")
            }
        }
        error("Слишком много перенаправлений")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun notifyAvailable(context: Context, r: Release, downloaded: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступна версия ${r.versionName}")
            .setContentText(if (downloaded) "Скачана, нажмите, чтобы установить" else "Нажмите, чтобы обновить")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(1001, n)
        } catch (_: SecurityException) {
            // notifications are off: the banner inside the app still shows the update
        }
    }
}

/** Daily background check; on Wi-Fi it also downloads the APK so the update installs with one tap. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val r = Updater.fetchLatest()
        if (Updater.isNewer(r)) {
            val auto = Prefs(applicationContext).autoDownloadOnWifi && Updater.onUnmeteredNetwork(applicationContext)
            if (auto) Updater.download(applicationContext, r)
            Updater.notifyAvailable(applicationContext, r, auto)
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
