package ltd.pion.calcalc.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

object HistoryAction {
    const val ADD = "add"
    const val EDIT = "edit"
    const val AI = "ai"
    const val DELETE = "delete"
    const val RESTORE = "restore"
}

/**
 * What happened to an entry and when: added, changed, counted by the AI, deleted, restored. [snapshot] is the
 * whole entry at that moment, so a deleted one can be brought back from here.
 */
@Entity(tableName = "history")
data class History(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: String,
    val action: String,
    val at: Long = System.currentTimeMillis(),
    val title: String,
    val kcal: Int?,
    val day: String,
    val snapshot: String,
) {
    fun entry(): Entry = EntryJson.from(JSONObject(snapshot))

    companion object {
        fun of(action: String, e: Entry) = History(
            entryId = e.id, action = action, title = e.title, kcal = e.kcal, day = e.day, snapshot = EntryJson.to(e).toString(),
        )
    }
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT 500")
    fun recent(): Flow<List<History>>

    @Insert
    suspend fun add(h: History)

    /** Older than [before] goes away, so the log does not grow forever. */
    @Query("DELETE FROM history WHERE at < :before")
    suspend fun prune(before: Long)
}

object EntryJson {
    fun to(e: Entry): JSONObject = JSONObject()
        .put("id", e.id).put("type", e.type).put("rawText", e.rawText).put("title", e.title)
        .putOpt("kcal", e.kcal).put("estimated", e.estimated)
        .putOpt("protein", e.protein).putOpt("fat", e.fat).putOpt("carbs", e.carbs)
        .put("status", e.status).put("day", e.day).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt)
        .put("editedByUser", e.editedByUser)
        .putOpt("question", e.question).putOpt("options", e.options).putOpt("answer", e.answer)
        .putOpt("photo", e.photo).putOpt("items", e.items)

    fun from(j: JSONObject): Entry {
        fun s(k: String) = if (j.has(k) && !j.isNull(k)) j.getString(k) else null
        fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.getDouble(k) else null
        return Entry(
            id = j.getString("id"), type = j.getString("type"), rawText = j.optString("rawText"), title = j.optString("title"),
            kcal = if (j.has("kcal") && !j.isNull("kcal")) j.getInt("kcal") else null,
            estimated = j.optBoolean("estimated"), protein = d("protein"), fat = d("fat"), carbs = d("carbs"),
            status = j.optString("status", EntryStatus.DONE), day = j.getString("day"),
            createdAt = j.optLong("createdAt", System.currentTimeMillis()), updatedAt = System.currentTimeMillis(),
            editedByUser = j.optBoolean("editedByUser"),
            question = s("question"), options = s("options"), answer = s("answer"), photo = s("photo"), items = s("items"),
        )
    }
}
