package ltd.pion.calcalc.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.Weight
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google sign-in through the Identity authorization API: one consent screen gives an access token for
 * the user's own Drive files (only those CalCalc creates) and their e-mail, which the AI script checks.
 */
object GoogleAuth {
    private val scopes = listOf(
        Scope("https://www.googleapis.com/auth/drive.file"),
        Scope("https://www.googleapis.com/auth/userinfo.email"),
    )

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** The user has to pick an account or confirm access: launch this from an activity. */
        data class NeedsUser(val intent: PendingIntent) : Result
    }

    suspend fun authorize(context: Context): Result {
        val req = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        val res = Identity.getAuthorizationClient(context).authorize(req).await()
        val pi = res.pendingIntent
        return if (res.hasResolution() && pi != null) Result.NeedsUser(pi) else Result.Token(res.accessToken!!)
    }

    fun tokenFromIntent(context: Context, data: Intent?): String? =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken

    /** Silent token for background work; null when the user must sign in again in the app. */
    suspend fun silentToken(context: Context): String? =
        (runCatching { authorize(context) }.getOrNull() as? Result.Token)?.accessToken

    suspend fun email(token: String): String = withContext(Dispatchers.IO) {
        JSONObject(Http.get("https://www.googleapis.com/oauth2/v3/userinfo", token)).getString("email")
    }
}

/** The user's own spreadsheet: CalCalc creates it on their Drive and rewrites it with the diary after each sync. */
object Sheets {
    private const val API = "https://sheets.googleapis.com/v4/spreadsheets"
    private const val DIARY = "Дневник"
    private const val DAYS = "По дням"
    private const val WEIGHT = "Вес"

    suspend fun create(token: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("properties", JSONObject().put("title", "CalCalc"))
            .put("sheets", JSONArray().apply {
                listOf(DIARY, DAYS, WEIGHT).forEach { put(JSONObject().put("properties", JSONObject().put("title", it))) }
            })
        JSONObject(Http.post(API, token, body.toString())).getString("spreadsheetId")
    }

    suspend fun exists(token: String, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { Http.get("$API/$id?fields=spreadsheetId", token) }.isSuccess
    }

    suspend fun mirror(token: String, id: String, entries: List<Entry>, weights: List<Weight>, norm: Int) = withContext(Dispatchers.IO) {
        val diary = JSONArray().put(row("Дата", "Время", "Тип", "Запись", "Ккал", "Белки", "Жиры", "Углеводы", "Статус", "Исходный текст"))
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
        entries.forEach { e ->
            diary.put(
                row(
                    e.day, time.format(java.util.Date(e.createdAt)), typeName(e.type), e.title,
                    e.kcal ?: "", e.protein ?: "", e.fat ?: "", e.carbs ?: "", statusName(e.status), e.rawText,
                )
            )
        }
        val days = JSONArray().put(row("Дата", "Съедено", "Сожжено", "Норма", "Баланс"))
        entries.groupBy { it.day }.toSortedMap().forEach { (day, list) ->
            val eaten = list.mapNotNull { it.kcal }.filter { it > 0 }.sum()
            val burned = -list.mapNotNull { it.kcal }.filter { it < 0 }.sum()
            days.put(row(day, eaten, burned, norm, eaten - burned - norm))
        }
        val w = JSONArray().put(row("Дата", "Вес, кг"))
        weights.forEach { w.put(row(it.day, it.kg)) }

        val ranges = listOf(DIARY to diary, DAYS to days, WEIGHT to w)
        Http.post(
            "$API/$id/values:batchClear", token,
            JSONObject().put("ranges", JSONArray(ranges.map { "'${it.first}'" })).toString(),
        )
        Http.post(
            "$API/$id/values:batchUpdate", token,
            JSONObject()
                .put("valueInputOption", "RAW")
                .put("data", JSONArray(ranges.map { (name, rows) -> JSONObject().put("range", "'$name'!A1").put("values", rows) }))
                .toString(),
        )
    }

    fun url(id: String) = "https://docs.google.com/spreadsheets/d/$id"

    private fun row(vararg v: Any) = JSONArray(v.toList())

    private fun typeName(t: String) = when (t) {
        "food" -> "Еда"
        "activity" -> "Активность"
        else -> "Вручную"
    }

    private fun statusName(s: String) = when (s) {
        "done" -> "готово"
        "needs_answer" -> "нужен ответ"
        "failed" -> "ошибка"
        else -> "ждёт ИИ"
    }
}

/** The shared AI on Dimon's PC, reached through his Apps Script, which checks the Google token and the allowlist. */
object Ai {
    class Answer(
        val ok: Boolean,
        val retry: Boolean,
        val error: String?,
        val title: String?,
        val kcal: Int?,
        val protein: Double?,
        val fat: Double?,
        val carbs: Double?,
        val question: String?,
        val options: List<String>,
    )

    suspend fun parse(url: String, token: String, text: String, weightKg: Double, image: String? = null): Answer {
        val body = JSONObject().put("action", "parse").put("accessToken", token).put("text", text).put("weightKg", weightKg)
        if (image != null) body.put("image", image)
        val j = JSONObject(Script.post(url, body.toString()))
        fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.getDouble(k) else null
        // optString turns a JSON null into the text "null", which once showed up as the AI's question
        fun s(k: String) = if (j.has(k) && !j.isNull(k)) j.getString(k).ifBlank { null } else null
        val opts = j.optJSONArray("options")
        return Answer(
            ok = j.optBoolean("ok"),
            retry = j.optBoolean("retry"),
            error = s("error"),
            title = s("title"),
            kcal = if (j.has("kcal") && !j.isNull("kcal")) j.getInt("kcal") else null,
            protein = d("protein"), fat = d("fat"), carbs = d("carbs"),
            question = s("question"),
            options = if (opts == null) emptyList() else (0 until opts.length()).map { opts.getString(it) },
        )
    }
}

object Http {
    fun get(url: String, token: String): String = request("GET", url, token, null)
    fun post(url: String, token: String, json: String): String = request("POST", url, token, json)

    private fun request(method: String, url: String, token: String, json: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(json.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, text)
        return text
    }
}

class HttpError(val code: Int, body: String) : Exception("HTTP $code: ${body.take(200)}")
