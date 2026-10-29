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
import ltd.pion.calcalc.data.Favorite
import ltd.pion.calcalc.data.History
import ltd.pion.calcalc.data.HistoryAction
import ltd.pion.calcalc.data.Template
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
import ltd.pion.calcalc.sync.Photos
import ltd.pion.calcalc.sync.Sheets
import ltd.pion.calcalc.sync.Steps
import ltd.pion.calcalc.sync.StepsState
import ltd.pion.calcalc.sync.SyncWorker
import ltd.pion.calcalc.update.UpdateState
import ltd.pion.calcalc.update.Updater
import java.time.LocalDate

data class QuickItem(val template: Template, val favorite: Boolean)

data class SyncStatus(val running: Boolean, val text: String?, val warn: Boolean)

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

    val theme: StateFlow<String> = prefs.changes().map { prefs.theme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.theme)

    val profile: StateFlow<Profile> = prefs.changes().map { prefs.profile }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.profile)

    val entries: StateFlow<List<Entry>> = _day.flatMapLatest { db.entries().forDay(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totals: StateFlow<List<DayTotal>> = db.entries().totals()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<DaySummary> = combine(entries, profile, weight) { list, p, w -> summarize(list, p, w) }.stateIn(viewModelScope, SharingStarted.Eagerly, DaySummary(2000, 0, 0, 0))

    /** One day's entries and totals, for the day pages next to the shown one. */
    fun dayData(day: LocalDate) = combine(db.entries().forDay(day.toString()), profile, weight) { list, p, w ->
        list to summarize(list, p, w)
    }

    private fun summarize(list: List<Entry>, p: Profile, w: Double) = DaySummary(
        norm = Norm.daily(p, w),
        eaten = list.mapNotNull { it.kcal }.filter { it > 0 }.sum(),
        burned = -list.mapNotNull { it.kcal }.filter { it < 0 }.sum(),
        pending = list.count { it.kcal == null },
    )

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
        viewModelScope.launch {
            val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            db.history().prune(System.currentTimeMillis() - 180L * 24 * 3600 * 1000)
            // photos of entries deleted a month ago and more are no longer needed for «Восстановить»
            val used = db.entries().all().mapNotNull { it.photo }.toSet()
            java.io.File(app.filesDir, "photos").listFiles()?.filter { it.path !in used && it.lastModified() < monthAgo }?.forEach { it.delete() }
        }
    }

    /** Saves the entry and writes what happened to the log. */
    private suspend fun save(e: Entry, action: String) {
        db.entries().upsert(e)
        db.history().add(History.of(action, e))
    }

    /** Everything done with the entries, newest first, for the log screen. */
    val history: StateFlow<List<History>> = db.history().recent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ids of entries that exist now: a deleted one in the log can be brought back only if it is not here. */
    val entryIds: StateFlow<Set<String>> = db.entries().ids().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Brings a deleted entry back from the log, as it was when it was deleted. */
    fun restoreFromLog(h: History) = viewModelScope.launch {
        val e = h.entry().let { if (it.photo != null && !java.io.File(it.photo).exists()) it.copy(photo = null) else it }
        save(e.copy(synced = false), HistoryAction.RESTORE)
        SyncWorker.enqueue(getApplication())
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
            save(entry, HistoryAction.ADD)
            SyncWorker.enqueue(getApplication())
        }
    }

    val favorites: StateFlow<List<Favorite>> = db.favorites().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Quick buttons: starred food first, then what was eaten often in the last two months. */
    val quick: StateFlow<List<QuickItem>> = combine(
        favorites, db.entries().frequent(System.currentTimeMillis() - 60L * 24 * 3600 * 1000),
    ) { favs, often ->
        val starred = favs.map { QuickItem(Template(it.title, it.rawText, it.type, it.kcal, it.protein, it.fat, it.carbs), true) }
        val names = favs.map { it.title }.toSet()
        (starred + often.filter { it.title !in names }.map { QuickItem(it, false) }).take(12)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleFavorite(e: Entry) = viewModelScope.launch {
        if (favorites.value.any { it.title == e.title }) db.favorites().delete(e.title)
        else e.kcal?.takeIf { it > 0 }?.let {
            db.favorites().upsert(Favorite(e.title, e.rawText.ifBlank { e.title }, e.type, it, e.protein, e.fat, e.carbs))
        }
    }

    /** One tap on a quick button: the same food again, already counted; [factor] is the portion (×0.5, ×2). */
    fun repeat(t: Template, factor: Double = 1.0) = viewModelScope.launch {
        fun scale(v: Double?) = v?.let { Math.round(it * factor * 10) / 10.0 }
        val title = if (factor == 1.0) t.title else t.title + " ×" + (if (factor % 1.0 == 0.0) factor.toInt().toString() else factor.toString().replace('.', ','))
        save(
            Entry(
                type = t.type, rawText = t.rawText, title = title, kcal = Math.round(t.kcal * factor).toInt(),
                protein = scale(t.protein), fat = scale(t.fat), carbs = scale(t.carbs), status = EntryStatus.DONE, day = _day.value.toString(),
            ),
            HistoryAction.ADD,
        )
        SyncWorker.enqueue(getApplication())
    }

    /** A past meal (or a whole day) once more today, with the same numbers. */
    fun repeatToToday(list: List<Entry>) = viewModelScope.launch {
        val today = LocalDate.now().toString()
        list.filter { it.kcal != null }.forEach {
            save(
                it.copy(
                    id = java.util.UUID.randomUUID().toString(), day = today, status = EntryStatus.DONE, photo = null,
                    synced = false, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                ),
                HistoryAction.ADD,
            )
        }
        SyncWorker.enqueue(getApplication())
    }

    private val query = MutableStateFlow("")

    /** Food from the history that matches what is being typed. */
    val suggestions: StateFlow<List<QuickItem>> = query.flatMapLatest { q ->
        if (q.length < 2) kotlinx.coroutines.flow.flowOf(emptyList())
        else db.entries().search(q).map { list -> list.map { QuickItem(it, favorites.value.any { f -> f.title == it.title }) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setQuery(q: String) {
        query.value = q.trim()
    }

    /** A photo of the food from the camera: shrunk and kept with the entry, the AI counts it from the picture. */
    fun addPhoto(uri: android.net.Uri, text: String) = viewModelScope.launch {
        val id = java.util.UUID.randomUUID().toString()
        val path = Photos.save(getApplication(), uri, id) ?: return@launch
        val words = text.trim()
        save(
            Entry(
                id = id, type = EntryType.FOOD, rawText = words, title = words.ifBlank { "Фото еды" }, kcal = null,
                status = EntryStatus.QUEUED, day = _day.value.toString(), photo = path,
            ),
            HistoryAction.ADD,
        )
        SyncWorker.enqueue(getApplication())
    }

    /** The user rewrote what they ate or did: the entry is read again from the new text, food goes back to the AI. */
    fun rewrite(entry: Entry, text: String) {
        if (text.isBlank()) return
        val parsed = parseEntry(text.trim(), entry.day).copy(id = entry.id, createdAt = entry.createdAt)
        // the photo stays with food: the new words only add to what the picture shows
        val fresh = if (parsed.type == EntryType.FOOD) parsed.copy(photo = entry.photo) else parsed
        viewModelScope.launch {
            save(fresh, HistoryAction.EDIT)
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
        save(
            entry.copy(
                kcal = kcal, estimated = false, status = EntryStatus.DONE, editedByUser = true,
                synced = false, updatedAt = System.currentTimeMillis(),
            ),
            HistoryAction.EDIT,
        )
        SyncWorker.enqueue(getApplication())
    }

    /** The photo file stays a month after deletion, so the entry can still be restored from the log. */
    fun delete(entry: Entry) = viewModelScope.launch {
        db.entries().delete(entry.id)
        db.history().add(History.of(HistoryAction.DELETE, entry))
    }

    /** «Отменить» after a swipe: the entry comes back as it was. */
    fun restore(entry: Entry) = viewModelScope.launch {
        save(entry, HistoryAction.RESTORE)
        SyncWorker.enqueue(getApplication())
    }

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
        prefs.aiProblem = null
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

    /** What the sync is doing, in words for the profile: sending now, what is waiting and why, or when it last worked. */
    val syncStatus: StateFlow<SyncStatus> = combine(
        androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("sync"),
        db.entries().waitingCount(),
        prefs.changes(),
    ) { infos, waiting, _ ->
        val running = infos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
        val text = when {
            running -> if (waiting > 0) "Отправляю ИИ: $waiting" else "Обновляю таблицу…"
            prefs.aiProblem != null -> prefs.aiProblem!! + if (waiting > 0) " (ждут: $waiting)" else ""
            waiting > 0 -> "Ждут ИИ: $waiting, отправлю, как будет связь"
            prefs.lastSync > 0 -> "Всё посчитано, таблица обновлена в " +
                java.time.Instant.ofEpochMilli(prefs.lastSync).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            else -> null
        }
        SyncStatus(running, text, warn = !running && (prefs.aiProblem != null))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus(false, null, false))

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
