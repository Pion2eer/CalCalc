package ltd.pion.calcalc.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ltd.pion.calcalc.data.AppDb
import ltd.pion.calcalc.data.DayTotal
import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.EntryStatus
import ltd.pion.calcalc.data.EntryType
import ltd.pion.calcalc.data.Prefs
import ltd.pion.calcalc.data.Profile
import ltd.pion.calcalc.data.Weight
import ltd.pion.calcalc.domain.Activities
import ltd.pion.calcalc.domain.InputParser
import ltd.pion.calcalc.domain.Norm
import ltd.pion.calcalc.domain.Parsed
import ltd.pion.calcalc.sync.GoogleAuth
import ltd.pion.calcalc.sync.Sheets
import ltd.pion.calcalc.sync.Steps
import ltd.pion.calcalc.sync.StepsState
import ltd.pion.calcalc.sync.SyncWorker
import ltd.pion.calcalc.update.UpdateState
import ltd.pion.calcalc.update.Updater
import java.time.LocalDate

data class DaySummary(
    val norm: Int,
    val eaten: Int,
    val burned: Int,
    val pending: Int,
) {
    val left get() = norm + burned - eaten
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    val prefs = Prefs(app)

    private val _day = MutableStateFlow(LocalDate.now())
    val day: StateFlow<LocalDate> = _day.asStateFlow()

    val weight: StateFlow<Double> = db.weights().latest().map { it?.kg ?: 75.0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 75.0)

    val weights: StateFlow<List<Weight>> = db.weights().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val profile: StateFlow<Profile> = prefs.changes().map { prefs.profile }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.profile)

    val entries: StateFlow<List<Entry>> = _day.flatMapLatest { db.entries().forDay(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totals: StateFlow<List<DayTotal>> = db.entries().totals()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<DaySummary> = combine(entries, profile, weight) { list, p, w ->
        DaySummary(
            norm = Norm.daily(p, w),
            eaten = list.mapNotNull { it.kcal }.filter { it > 0 }.sum(),
            burned = -list.mapNotNull { it.kcal }.filter { it < 0 }.sum(),
            pending = list.count { it.kcal == null },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DaySummary(2000, 0, 0, 0))

    /** Shown instead of «ИИ считает» when food cannot be counted yet, with what to do about it. */
    val aiBlocker: StateFlow<String?> = prefs.changes().map {
        when {
            prefs.accountEmail == null -> "Войдите через Google в «Профиле», и ИИ посчитает еду"
            prefs.aiUrl.isBlank() -> "ИИ ещё не подключён, записи подождут"
            else -> prefs.aiProblem
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    init {
        checkUpdate(silent = true)
    }

    private val _steps = MutableStateFlow(StepsState.OFF)
    val steps: StateFlow<StepsState> = _steps.asStateFlow()

    /** What Health Connect has for the shown day, so the profile can say whether steps arrive at all. */
    private val _stepCount = MutableStateFlow<Long?>(null)
    val stepCount: StateFlow<Long?> = _stepCount.asStateFlow()

    /**
     * Today's steps from Health Connect become one activity entry of the day, «Шаги: 8 400». Walking typed in by
     * hand is already inside those steps, so only the rest is added. An entry the user corrected is left alone.
     */
    fun refreshSteps() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        _steps.value = runCatching { Steps.state(ctx) }.getOrDefault(StepsState.UNAVAILABLE)
        if (_steps.value != StepsState.ON) return@launch
        val day = _day.value
        val count = runCatching { Steps.count(ctx, day) }.getOrNull() ?: return@launch
        _stepCount.value = count
        val id = "steps-$day"
        val old = db.entries().byId(id)
        if (old?.editedByUser == true || (old == null && count == 0L)) return@launch
        val walked = db.entries().forDayOnce(day.toString())
            .filter { it.type == EntryType.ACTIVITY && it.id != id && it.title.startsWith("Ходьба") }
            .sumOf { -(it.kcal ?: 0) }
        val kcal = (Activities.stepsKcal(count, weight.value) - walked).coerceAtLeast(0)
        val title = "Шаги: " + String.format(java.util.Locale("ru"), "%,d", count)
        if (old != null && old.title == title && old.kcal == -kcal) return@launch
        db.entries().upsert(
            Entry(
                id = id, type = EntryType.ACTIVITY, rawText = title, title = title, kcal = -kcal,
                status = EntryStatus.DONE, day = day.toString(), createdAt = old?.createdAt ?: System.currentTimeMillis(),
            )
        )
        SyncWorker.enqueue(ctx)
    }

    fun shiftDay(by: Long) {
        val next = _day.value.plusDays(by)
        if (!next.isAfter(LocalDate.now())) {
            _day.value = next
            refreshSteps()
        }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        val entry = parseEntry(text, _day.value.toString())
        viewModelScope.launch {
            db.entries().upsert(entry)
            SyncWorker.enqueue(getApplication())
        }
    }

    /** The user rewrote what they ate or did: the entry is read again from the new text, food goes back to the AI. */
    fun rewrite(entry: Entry, text: String) {
        if (text.isBlank()) return
        val fresh = parseEntry(text.trim(), entry.day).copy(id = entry.id, createdAt = entry.createdAt)
        viewModelScope.launch {
            db.entries().upsert(fresh)
            SyncWorker.enqueue(getApplication())
        }
    }

    private fun parseEntry(text: String, day: String) = when (val p = InputParser.parse(text)) {
        is Parsed.Manual -> Entry(
            type = EntryType.MANUAL, rawText = text, title = p.title, kcal = p.kcal,
            status = EntryStatus.DONE, day = day,
        )
        is Parsed.Workout -> Entry(
            type = EntryType.ACTIVITY, rawText = text,
            title = "${p.activity.name}, ${p.minutes} мин",
            kcal = -Activities.netKcal(p.activity.met, weight.value, p.minutes, p.intensity),
            status = EntryStatus.DONE, day = day,
        )
        is Parsed.Food -> Entry(
            type = EntryType.FOOD, rawText = text, title = p.text, kcal = null,
            status = EntryStatus.QUEUED, day = day,
        )
    }

    fun setKcal(entry: Entry, kcal: Int) = viewModelScope.launch {
        db.entries().update(
            entry.copy(
                kcal = kcal, estimated = false, status = EntryStatus.DONE, editedByUser = true,
                synced = false, updatedAt = System.currentTimeMillis(),
            )
        )
        SyncWorker.enqueue(getApplication())
    }

    fun delete(entry: Entry) = viewModelScope.launch { db.entries().delete(entry.id) }

    fun saveProfile(p: Profile, weightKg: Double?) = viewModelScope.launch {
        prefs.profile = p
        prefs.profileDone = true
        if (weightKg != null) db.weights().upsert(Weight(LocalDate.now().toString(), weightKg))
    }

    fun saveWeight(kg: Double) = viewModelScope.launch {
        db.weights().upsert(Weight(LocalDate.now().toString(), kg))
    }

    private val _account = MutableStateFlow(prefs.accountEmail)
    val account: StateFlow<String?> = _account.asStateFlow()
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /** Starts Google sign-in; when the user has to pick an account, [launch] opens the system screen. */
    fun signIn(launch: (android.app.PendingIntent) -> Unit) = viewModelScope.launch {
        _authError.value = null
        try {
            when (val r = GoogleAuth.authorize(getApplication())) {
                is GoogleAuth.Result.NeedsUser -> launch(r.intent)
                is GoogleAuth.Result.Token -> finishSignIn(r.accessToken)
            }
        } catch (e: Exception) {
            _authError.value = "Не удалось войти: ${e.message}"
        }
    }

    fun onSignInResult(data: android.content.Intent?) = viewModelScope.launch {
        try {
            val token = GoogleAuth.tokenFromIntent(getApplication(), data)
            if (token != null) finishSignIn(token) else _authError.value = "Вход отменён"
        } catch (e: Exception) {
            _authError.value = "Вход отменён"
        }
    }

    private suspend fun finishSignIn(token: String) {
        val email = GoogleAuth.email(token)
        if (prefs.accountEmail != email) {
            // another account means another spreadsheet
            prefs.sheetId = null
            prefs.lastSheetSync = 0
        }
        prefs.accountEmail = email
        _account.value = email
        SyncWorker.enqueue(getApplication())
    }

    fun signOut() {
        prefs.accountEmail = null
        _account.value = null
    }

    fun sheetUrl(): String? = prefs.sheetId?.let { Sheets.url(it) }

    /** A tap on one of the AI's answer buttons: the entry goes back to the AI with the answer. */
    fun answer(entry: Entry, option: String) = viewModelScope.launch {
        db.entries().update(
            entry.copy(status = EntryStatus.QUEUED, answer = option, updatedAt = System.currentTimeMillis())
        )
        SyncWorker.enqueue(getApplication())
    }

    fun syncNow() = SyncWorker.enqueue(getApplication())

    fun checkUpdate(silent: Boolean = false) {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        if (!silent) _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = try {
                val r = Updater.fetchLatest()
                when {
                    !Updater.isNewer(r) -> if (silent) UpdateState.Idle else UpdateState.UpToDate
                    Updater.apkFile(getApplication(), r).exists() -> UpdateState.ReadyToInstall(r, Updater.apkFile(getApplication(), r))
                    else -> UpdateState.Available(r)
                }
            } catch (e: Exception) {
                if (silent) UpdateState.Idle else UpdateState.Error("Не удалось проверить: ${e.message ?: "нет связи"}")
            }
        }
    }

    fun downloadAndInstall() {
        val r = when (val s = _update.value) {
            is UpdateState.Available -> s.release
            is UpdateState.ReadyToInstall -> {
                install(s)
                return
            }
            else -> return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(r, 0)
            _update.value = try {
                val f = Updater.download(getApplication(), r) { _update.value = UpdateState.Downloading(r, it) }
                UpdateState.ReadyToInstall(r, f).also { install(it) }
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Ошибка загрузки")
            }
        }
    }

    private fun install(s: UpdateState.ReadyToInstall) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) Updater.askInstallPermission(ctx) else Updater.install(ctx, s.file)
    }
}
