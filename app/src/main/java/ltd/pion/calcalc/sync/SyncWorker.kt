package ltd.pion.calcalc.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ltd.pion.calcalc.data.AppDb
import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.EntryStatus
import ltd.pion.calcalc.data.Prefs
import ltd.pion.calcalc.domain.Norm
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs whenever there is a network: sends waiting food to the shared AI, then rewrites the user's own
 * spreadsheet. Without a Google sign-in it does nothing and everything stays on the phone.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.accountEmail == null) return Result.success()
        val token = GoogleAuth.silentToken(applicationContext)
        if (token == null) {
            // Google wants the user to confirm the sign-in again: say so on the screen and in the journal, instead of
            // leaving the food waiting without a word
            prefs.aiProblem = "Google просит войти заново: «Профиль» → «Войти через Google»"
            report(prefs, "", "no Google token for ${prefs.accountEmail}")
            return Result.success()
        }
        val db = AppDb.get(applicationContext)
        val weight = db.weights().latest().first()?.kg ?: 75.0

        var again = false
        if (prefs.aiUrl.isNotBlank()) {
            db.entries().requeueNullQuestions()
            for (e in db.entries().forAi()) {
                val a = try {
                    Ai.parse(prefs.aiUrl, token, aiText(e), weight, e.photo?.let { Photos.base64(it) })
                } catch (ex: Exception) {
                    report(prefs, token, "parse failed: $ex")
                    prefs.aiProblem = "Нет связи с ИИ, записи посчитаются позже"
                    again = true
                    break
                }
                prefs.aiProblem = when {
                    a.retry -> a.error ?: "Компьютер с ИИ выключен, записи посчитаются позже"
                    !a.ok && a.error != null && a.error.startsWith("Нет доступа") -> a.error
                    else -> null
                }
                // no access is about the account, not this entry: keep it waiting
                if (!a.ok && a.error != null && a.error.startsWith("Нет доступа")) break
                // the entry may have been edited or deleted while the AI was thinking
                val now = db.entries().byId(e.id) ?: continue
                if (now.editedByUser) continue
                when {
                    a.ok && a.question != null && e.answer == null -> db.entries().update(
                        now.copy(
                            status = EntryStatus.NEEDS_ANSWER, question = a.question,
                            options = JSONArray(a.options).toString(),
                            kcal = a.kcal, estimated = a.kcal != null, title = a.title ?: now.title,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    a.ok -> db.entries().update(
                        now.copy(
                            status = EntryStatus.DONE, kcal = a.kcal, estimated = false,
                            title = a.title ?: now.title, protein = a.protein, fat = a.fat, carbs = a.carbs,
                            question = null, options = null, updatedAt = System.currentTimeMillis(),
                        )
                    )
                    a.retry -> {
                        // the PC is off or the model is still loading: try the rest later
                        again = true
                        break
                    }
                    else -> db.entries().update(now.copy(status = EntryStatus.FAILED, updatedAt = System.currentTimeMillis()))
                }
            }
        }

        try {
            mirrorSheet(prefs, token, weight)
        } catch (ex: Exception) {
            report(prefs, token, "sheet sync failed: $ex")
            again = true
        }
        return if (again) Result.retry() else Result.success()
    }

    /** Errors go to the journal of the AI script, so they can be looked at without the phone. */
    private suspend fun report(prefs: Prefs, token: String, message: String) {
        if (prefs.aiUrl.isBlank()) return
        runCatching {
            Script.post(
                prefs.aiUrl,
                org.json.JSONObject().put("action", "clientLog").put("accessToken", token)
                    .put("message", "${ltd.pion.calcalc.BuildConfig.VERSION_NAME}: ${message.take(500)}").toString(),
            )
        }
    }

    private fun aiText(e: Entry) =
        if (e.answer != null && e.question != null) "${e.rawText}\nУточнение: ${e.question} — ${e.answer}" else e.rawText

    private suspend fun mirrorSheet(prefs: Prefs, token: String, weight: Double) {
        val db = AppDb.get(applicationContext)
        val entries = db.entries().all()
        val changed = entries.maxOfOrNull { maxOf(it.updatedAt, it.createdAt) } ?: 0
        if (changed <= prefs.lastSheetSync && prefs.sheetId != null) return
        var id = prefs.sheetId
        if (id == null || !Sheets.exists(token, id)) {
            id = Sheets.create(token)
            prefs.sheetId = id
        }
        Sheets.mirror(token, id, entries, db.weights().all().first(), Norm.daily(prefs.profile, weight))
        prefs.lastSheetSync = System.currentTimeMillis()
    }

    companion object {
        // A request waiting in its backoff (minutes, later hours) is replaced, so a fresh entry goes to the AI right
        // away; a running one is not cancelled, because that throws away the AI's answer and asks it again
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val wm = WorkManager.getInstance(context)
            val running = runCatching {
                runBlocking { wm.getWorkInfosForUniqueWorkFlow("sync").first() }.any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            wm.enqueueUniqueWork("sync", if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Apps Script web apps answer a POST with a redirect to googleusercontent.com, which must be followed with a GET. */
object Script {
    suspend fun post(url: String, json: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location")
            c.disconnect()
            val g = URL(loc).openConnection() as HttpURLConnection
            g.connectTimeout = 20_000
            g.readTimeout = 60_000
            g.inputStream.bufferedReader().use { it.readText() }
        } else {
            c.inputStream.bufferedReader().use { it.readText() }
        }
    }
}
