package ltd.pion.calcalc.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

object EntryType {
    const val FOOD = "food"
    const val ACTIVITY = "activity"
    const val MANUAL = "manual"
}

object EntryStatus {
    /** Waiting to be sent to the AI. */
    const val QUEUED = "queued"
    const val PROCESSING = "processing"
    const val NEEDS_ANSWER = "needs_answer"
    const val DONE = "done"
    const val FAILED = "failed"
}

/**
 * One diary line. It is saved the moment the user taps "add", with the raw text kept forever;
 * calories may arrive later from the AI. [kcal] is positive for food and negative for burned energy.
 */
@Entity(tableName = "entries")
data class Entry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String,
    val rawText: String,
    val title: String,
    val kcal: Int?,
    val estimated: Boolean = false,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbs: Double? = null,
    val status: String,
    val day: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val editedByUser: Boolean = false,
    val synced: Boolean = false,
    /** A clarifying question from the AI and its button options (JSON array), answered with [answer]. */
    val question: String? = null,
    val options: String? = null,
    val answer: String? = null,
)

@Entity(tableName = "weights")
data class Weight(
    @PrimaryKey val day: String,
    val kg: Double,
)

data class DayTotal(
    val day: String,
    val eaten: Int,
    val burned: Int,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE day = :day ORDER BY createdAt")
    fun forDay(day: String): Flow<List<Entry>>

    @Query(
        "SELECT day, " +
            "COALESCE(SUM(CASE WHEN kcal > 0 THEN kcal ELSE 0 END), 0) AS eaten, " +
            "COALESCE(SUM(CASE WHEN kcal < 0 THEN -kcal ELSE 0 END), 0) AS burned " +
            "FROM entries GROUP BY day ORDER BY day DESC"
    )
    fun totals(): Flow<List<DayTotal>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: String): Entry?

    @Query("SELECT * FROM entries WHERE synced = 0 ORDER BY createdAt")
    suspend fun unsynced(): List<Entry>

    /** Entries that got the question «null» from an old app version go back to the AI to be counted properly. */
    @Query("UPDATE entries SET status = 'queued', question = NULL, options = NULL WHERE status = 'needs_answer' AND question = 'null'")
    suspend fun requeueNullQuestions()

    /** Food the AI still has to count: new entries and those whose question was just answered. */
    @Query("SELECT * FROM entries WHERE type = 'food' AND status IN ('queued', 'processing') ORDER BY createdAt")
    suspend fun forAi(): List<Entry>

    @Query("SELECT * FROM entries ORDER BY day, createdAt")
    suspend fun all(): List<Entry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: Entry)

    @Update
    suspend fun update(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE entries SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY day DESC LIMIT 1")
    fun latest(): Flow<Weight?>

    @Query("SELECT * FROM weights ORDER BY day")
    fun all(): Flow<List<Weight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(weight: Weight)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN question TEXT")
        db.execSQL("ALTER TABLE entries ADD COLUMN options TEXT")
        db.execSQL("ALTER TABLE entries ADD COLUMN answer TEXT")
    }
}

@Database(entities = [Entry::class, Weight::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun weights(): WeightDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "calcalc.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
