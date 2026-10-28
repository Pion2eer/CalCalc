package ltd.pion.calcalc.data

import android.content.Context
import android.content.SharedPreferences
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

data class ServerConfig(
    val scriptUrl: String = "",
    val token: String = "",
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

    var server: ServerConfig
        get() = ServerConfig(sp.getString("scriptUrl", "") ?: "", sp.getString("token", "") ?: "")
        set(s) = sp.edit().putString("scriptUrl", s.scriptUrl.trim()).putString("token", s.token.trim()).apply()

    var autoDownloadOnWifi: Boolean
        get() = sp.getBoolean("autoDownloadOnWifi", true)
        set(v) = sp.edit().putBoolean("autoDownloadOnWifi", v).apply()

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
