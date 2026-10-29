package ltd.pion.calcalc.data

import android.content.Context
import android.content.SharedPreferences
import ltd.pion.calcalc.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Profile(
    val male: Boolean = true,
    val birthYear: Int = 1990,
    val heightCm: Int = 175,
    /** -1 lose, 0 keep, 1 gain */
    val goal: Int = 0,
)


class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("calcalc", Context.MODE_PRIVATE)

    var profile: Profile
        get() = Profile(
            male = sp.getBoolean("male", true),
            birthYear = sp.getInt("birthYear", 1990),
            heightCm = sp.getInt("heightCm", 175),
            goal = sp.getInt("goal", 0),
        )
        set(p) = sp.edit()
            .putBoolean("male", p.male)
            .putInt("birthYear", p.birthYear)
            .putInt("heightCm", p.heightCm)
            .putInt("goal", p.goal)
            .apply()

    /** Address of the shared AI script; empty means the one built into the app. */
    var aiUrl: String
        get() = (sp.getString("aiUrl", "") ?: "").ifBlank { BuildConfig.AI_SCRIPT_URL }
        set(v) = sp.edit().putString("aiUrl", v.trim()).apply()

    /** The Google account the user signed in with; null until they do. */
    var accountEmail: String?
        get() = sp.getString("accountEmail", null)
        set(v) = sp.edit().putString("accountEmail", v).apply()

    /** The user's own CalCalc spreadsheet on their Google Drive. */
    var sheetId: String?
        get() = sp.getString("sheetId", null)
        set(v) = sp.edit().putString("sheetId", v).apply()

    var lastSheetSync: Long
        get() = sp.getLong("lastSheetSync", 0)
        set(v) = sp.edit().putLong("lastSheetSync", v).apply()

    /** When a sync last finished without errors, for the status line in the profile. */
    var lastSync: Long
        get() = sp.getLong("lastSync", 0)
        set(v) = sp.edit().putLong("lastSync", v).apply()

    var autoDownloadOnWifi: Boolean
        get() = sp.getBoolean("autoDownloadOnWifi", true)
        set(v) = sp.edit().putBoolean("autoDownloadOnWifi", v).apply()

    /** Why food is not being counted right now (PC off, no access…); null when the AI answered last time. */
    var aiProblem: String?
        get() = sp.getString("aiProblem", null)
        set(v) = sp.edit().putString("aiProblem", v).apply()

    /** "dark", "light" or "system". */
    var theme: String
        get() = sp.getString("theme", "light") ?: "light"
        set(v) = sp.edit().putString("theme", v).apply()

    var profileDone: Boolean
        get() = sp.getBoolean("profileDone", false)
        set(v) = sp.edit().putBoolean("profileDone", v).apply()

    /** Emits on every change, so screens recompute the norm when the profile is edited. */
    fun changes(): Flow<Unit> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sp.registerOnSharedPreferenceChangeListener(l)
        trySend(Unit)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
}
