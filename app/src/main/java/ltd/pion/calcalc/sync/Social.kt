package ltd.pion.calcalc.sync

import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.EntryType
import org.json.JSONArray
import org.json.JSONObject

/** A friend or family member: an accepted link, or an invite waiting for an answer. */
data class Link(val email: String, val family: Boolean, val accepted: Boolean, val incoming: Boolean)

/** One dish from a friend's diary, as their app sent it. */
data class FriendFood(
    val day: String,
    val at: Long,
    val title: String,
    val kcal: Int,
    val protein: Double?,
    val fat: Double?,
    val carbs: Double?,
    val items: String?,
)

class SocialError(message: String) : Exception(message)

/** Friends and family through the shared script: invites by e-mail, and food seen only by accepted links. */
object Social {
    private suspend fun call(url: String, token: String, op: String, fill: JSONObject.() -> Unit = {}): JSONObject {
        val body = JSONObject().put("action", "social").put("op", op).put("accessToken", token).apply(fill)
        val j = JSONObject(Script.post(url, body.toString()))
        if (!j.optBoolean("ok")) throw SocialError(j.optString("error").ifBlank { "Не получилось, попробуйте позже" })
        return j
    }

    suspend fun list(url: String, token: String): List<Link> {
        val a = call(url, token, "list").optJSONArray("links") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Link(it.getString("email"), it.optString("kind") == "family", it.optString("status") == "accepted", it.optBoolean("incoming"))
        }
    }

    suspend fun invite(url: String, token: String, email: String, family: Boolean) {
        call(url, token, "invite") { put("email", email.trim().lowercase()); put("kind", if (family) "family" else "friend") }
    }

    suspend fun answer(url: String, token: String, email: String, accept: Boolean) {
        call(url, token, "answer") { put("email", email); put("accept", accept) }
    }

    suspend fun remove(url: String, token: String, email: String) {
        call(url, token, "remove") { put("email", email) }
    }

    /** Sends the last weeks of counted food; the script keeps it only while someone may see it. Returns whether it did. */
    suspend fun push(url: String, token: String, entries: List<Entry>): Boolean {
        val food = entries.filter { it.type != EntryType.ACTIVITY && (it.kcal ?: 0) > 0 }
            .sortedByDescending { it.createdAt }.take(600)
        val a = JSONArray()
        food.forEach { e ->
            a.put(
                JSONObject().put("day", e.day).put("at", e.createdAt).put("title", e.title).put("kcal", e.kcal)
                    .putOpt("protein", e.protein).putOpt("fat", e.fat).putOpt("carbs", e.carbs)
                    .putOpt("items", e.items?.let { runCatching { JSONArray(it) }.getOrNull() })
            )
        }
        return call(url, token, "push") { put("entries", a) }.optBoolean("shared")
    }

    suspend fun feed(url: String, token: String, email: String): List<FriendFood> {
        val a = call(url, token, "feed") { put("email", email) }.optJSONArray("entries") ?: JSONArray()
        fun JSONObject.d(k: String) = if (has(k) && !isNull(k)) getDouble(k) else null
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            FriendFood(
                it.optString("day"), it.optLong("at"), it.optString("title"), it.optInt("kcal"),
                it.d("protein"), it.d("fat"), it.d("carbs"), it.optJSONArray("items")?.toString(),
            )
        }.sortedByDescending { it.at }
    }
}
