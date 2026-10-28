package ltd.pion.calcalc.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ltd.pion.calcalc.data.AppDb
import ltd.pion.calcalc.data.EntryStatus
import ltd.pion.calcalc.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Sends queued entries to the Google Apps Script web app and pulls back the calories the AI on the PC filled in.
 * Runs whenever there is a network; with no script configured it does nothing and entries stay local.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cfg = Prefs(applicationContext).server
        if (cfg.scriptUrl.isBlank()) return Result.success()
        val dao = AppDb.get(applicationContext).entries()
        return try {
            val pending = dao.unsynced()
            if (pending.isNotEmpty()) {
                val arr = JSONArray()
                pending.forEach { e ->
                    arr.put(
                        JSONObject()
                            .put("id", e.id).put("type", e.type).put("text", e.rawText)
                            .put("title", e.title).put("kcal", e.kcal ?: JSONObject.NULL)
                            .put("status", e.status).put("day", e.day).put("createdAt", e.createdAt)
                            .put("editedByUser", e.editedByUser)
                    )
                }
                val body = JSONObject().put("action", "pushEntries").put("token", cfg.token).put("entries", arr)
                val res = JSONObject(Script.post(cfg.scriptUrl, body.toString()))
                if (!res.optBoolean("ok")) return Result.retry()
                dao.markSynced(pending.map { it.id })
            }
            pullChanges(cfg.scriptUrl, cfg.token)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun pullChanges(url: String, token: String) {
        val sp = applicationContext.getSharedPreferences("calcalc", Context.MODE_PRIVATE)
        val since = sp.getLong("changesSince", 0)
        val res = JSONObject(Script.get(url, mapOf("action" to "getChanges", "token" to token, "since" to since.toString())))
        if (!res.optBoolean("ok")) return
        val dao = AppDb.get(applicationContext).entries()
        val items = res.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until items.length()) {
            val c = items.getJSONObject(i)
            val local = dao.byId(c.getString("id")) ?: continue
            // a manual correction on the phone wins over a late answer from the AI
            if (local.editedByUser) continue
            dao.update(
                local.copy(
                    title = c.optString("title", local.title),
                    kcal = if (c.isNull("kcal")) local.kcal else c.getInt("kcal"),
                    estimated = false,
                    protein = c.optDoubleOrNull("protein") ?: local.protein,
                    fat = c.optDoubleOrNull("fat") ?: local.fat,
                    carbs = c.optDoubleOrNull("carbs") ?: local.carbs,
                    status = c.optString("status", EntryStatus.DONE),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        sp.edit().putLong("changesSince", res.optLong("now", since)).apply()
    }

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** Apps Script web apps answer with a redirect to googleusercontent.com, which must be followed with a GET. */
object Script {
    suspend fun post(url: String, json: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location")
            c.disconnect()
            read(URL(loc))
        } else {
            c.inputStream.bufferedReader().use { it.readText() }
        }
    }

    suspend fun get(url: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        read(URL(url + (if ('?' in url) "&" else "?") + q))
    }

    private fun read(u: URL): String {
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
