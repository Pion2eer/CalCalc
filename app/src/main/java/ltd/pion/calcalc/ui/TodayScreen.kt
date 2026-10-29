package ltd.pion.calcalc.ui

import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import ltd.pion.calcalc.sync.Photos
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Mic
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.speech.RecognizerIntent
import android.content.Intent
import java.time.temporal.ChronoUnit
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.EntryStatus
import ltd.pion.calcalc.data.EntryType
import ltd.pion.calcalc.update.UpdateState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))
private val Over = Color(0xFFEF5350)
val QuickAdds = listOf("Падел 1 час", "Ходьба 30 мин", "Зал 1 час", "+100 ккал")

@Composable
fun TodayScreen(vm: AppViewModel, modifier: Modifier) {
    val day by vm.day.collectAsState()
    val entries by vm.entries.collectAsState()
    val s by vm.summary.collectAsState()
    val update by vm.update.collectAsState()
    val blocker by vm.aiBlocker.collectAsState()
    var editing by remember { mutableStateOf<Entry?>(null) }
    // steps keep coming in during the day: read them again each time the screen is opened
    LifecycleResumeEffect(Unit) {
        vm.refreshSteps()
        onPauseOrDispose {}
    }

    TodayContent(
        day = day, today = LocalDate.now(), entries = entries, s = s, update = update, aiBlocker = blocker,
        onShiftDay = vm::shiftDay, onAdd = vm::add, onOpen = { editing = it },
        onAnswer = { e, o -> vm.answer(e, o) }, onUpdate = vm::downloadAndInstall, modifier = modifier,
        onAddPhoto = { uri, text -> vm.addPhoto(uri, text) },
        pageData = { d ->
            remember(d) { vm.dayData(d) }.collectAsState(emptyList<Entry>() to s.copy(eaten = 0, burned = 0, pending = 0)).value
        },
    )

    editing?.let { e ->
        EntryDialog(
            e, onDismiss = { editing = null },
            onSave = { vm.setKcal(e, it); editing = null },
            onRewrite = { vm.rewrite(e, it); editing = null },
            onDelete = { vm.delete(e); editing = null },
        )
    }
}

/** The whole screen from plain data, so screenshot tests can draw it without a database. */
@Composable
fun TodayContent(
    day: LocalDate,
    today: LocalDate,
    entries: List<Entry>,
    s: DaySummary,
    update: UpdateState,
    aiBlocker: String?,
    onShiftDay: (Long) -> Unit,
    onAdd: (String) -> Unit,
    onOpen: (Entry) -> Unit,
    onAnswer: (Entry, String) -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    initialInput: String = "",
    onAddPhoto: (android.net.Uri, String) -> Unit = { _, _ -> },
    /** Entries and totals of any day, for the pages around the shown one; without it only the shown day has data. */
    pageData: (@Composable (LocalDate) -> Pair<List<Entry>, DaySummary>)? = null,
) {
    var input by rememberSaveable { mutableStateOf(initialInput) }
    val send = {
        if (input.isNotBlank()) {
            onAdd(input.trim())
            input = ""
        }
    }

    Column(modifier.fillMaxSize()) {
        UpdateBanner(update, onUpdate)
        // days are pages: the next one slides in under the finger, older days lie to the left, today is the last page
        val index = ChronoUnit.DAYS.between(day, today).toInt()
        val pager = rememberPagerState(initialPage = index) { DaysBack }
        val shownIndex by rememberUpdatedState(index)
        val shift by rememberUpdatedState(onShiftDay)
        LaunchedEffect(index) { if (pager.currentPage != index) pager.animateScrollToPage(index) }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { if (it != shownIndex) shift((shownIndex - it).toLong()) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, beyondViewportPageCount = 1, key = { it }) { page ->
            val d = today.minusDays(page.toLong())
            val (list, sum) = pageData?.invoke(d)
                ?: if (d == day) entries to s else emptyList<Entry>() to s.copy(eaten = 0, burned = 0, pending = 0)
            DayPage(d, today, list, sum, aiBlocker, onShiftDay, onOpen, onAnswer)
        }

        InputBar(input, { input = it }, send, onQuick = onAdd, onPhoto = { uri -> onAddPhoto(uri, input); input = "" })
    }
}

/** How far back the pages go: ten years of days. */
private const val DaysBack = 3650

@Composable
private fun DayPage(
    day: LocalDate,
    today: LocalDate,
    entries: List<Entry>,
    s: DaySummary,
    aiBlocker: String?,
    onShiftDay: (Long) -> Unit,
    onOpen: (Entry) -> Unit,
    onAnswer: (Entry, String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item { DayHeader(day, today, onShiftDay) }
        item { SummaryCard(s, entries, aiBlocker) }

        val ask = entries.filter { it.status == EntryStatus.NEEDS_ANSWER && it.question != null }
        if (ask.isNotEmpty()) {
            item { SectionTitle("ИИ уточняет", null) }
            items(ask, key = { "q" + it.id }) { e -> QuestionCard(e, onAnswer = { onAnswer(e, it) }, onOpen = { onOpen(e) }) }
        }

        if (entries.isEmpty()) {
            item { EmptyHint() }
        } else {
            mealGroups(entries).forEach { (name, list) ->
                item(key = "h$name") { SectionTitle(name, if (list.all { it.kcal == null }) null else list.mapNotNull { it.kcal }.sum()) }
                item(key = "g$name") {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column { list.forEach { EntryRow(it, waiting = aiBlocker != null, onClick = { onOpen(it) }) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, onShiftDay: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onShiftDay(-1) }) { Icon(Icons.Filled.ChevronLeft, "Предыдущий день") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (day) {
                    today -> "Сегодня"
                    today.minusDays(1) -> "Вчера"
                    else -> day.format(dayFmt).replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            if (day == today || day == today.minusDays(1)) Text(
                day.format(dayFmt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton({ onShiftDay(1) }, enabled = day.isBefore(today)) {
            Icon(Icons.Filled.ChevronRight, "Следующий день")
        }
    }
}

@Composable
private fun SummaryCard(s: DaySummary, entries: List<Entry>, aiBlocker: String?) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SideStat("Съедено", s.eaten, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                Ring(s)
                SideStat("Сожжено", s.burned, Burn, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            val p = entries.sumOf { it.protein ?: 0.0 }
            val f = entries.sumOf { it.fat ?: 0.0 }
            val c = entries.sumOf { it.carbs ?: 0.0 }
            // a usual split of the daily norm: 20% protein, 30% fat, 50% carbs
            val budget = s.norm + s.burned
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Macro("Белки", p, budget * 0.20 / 4, Color(0xFF64B5F6), Modifier.weight(1f))
                Macro("Жиры", f, budget * 0.30 / 9, Color(0xFFF06292), Modifier.weight(1f))
                Macro("Углеводы", c, budget * 0.50 / 4, Color(0xFFFFD54F), Modifier.weight(1f))
            }
            if (s.pending > 0) Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (aiBlocker == null) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Warn)
                    Spacer(Modifier.width(8.dp))
                    Text("ИИ считает: ${s.pending} ${plural(s.pending, "запись", "записи", "записей")}", color = Warn, fontSize = 13.sp)
                } else {
                    Text(
                        "Ждут ИИ: ${s.pending}. $aiBlocker", color = Warn, fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SideStat(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontWeight = FontWeight.Bold, color = color, fontSize = 22.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun Ring(s: DaySummary) {
    val budget = (s.norm + s.burned).coerceAtLeast(1)
    val frac = (s.eaten.toFloat() / budget).coerceIn(0f, 1f)
    val over = s.left < 0
    val track = MaterialTheme.colorScheme.background
    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 12.dp.toPx()
            val box = Size(size.width - w, size.height - w)
            val tl = Offset(w / 2, w / 2)
            drawArc(track, -90f, 360f, false, tl, box, style = Stroke(w))
            if (frac > 0f) drawArc(if (over) Over else Accent, -90f, 360f * frac, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${kotlin.math.abs(s.left)}", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (over) Over else MaterialTheme.colorScheme.onSurface)
            Text(if (over) "ккал сверх" else "ккал осталось", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Macro(label: String, grams: Double, goal: Double, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (grams / goal.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = color, trackColor = MaterialTheme.colorScheme.background, drawStopIndicator = {}, gapSize = 0.dp,
        )
        Spacer(Modifier.height(4.dp))
        Text("${grams.toInt()} / ${goal.toInt()} г", fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(name: String, kcal: Int?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (kcal != null) Text(kcalText(kcal), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun EntryRow(e: Entry, waiting: Boolean, onClick: () -> Unit) {
    val k = e.kcal
    val activity = e.type == EntryType.ACTIVITY || (k != null && k < 0)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = e.photo?.let { path -> remember(path) { Photos.thumbnail(path, 120) } }
        if (thumb != null) Image(
            thumb.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop,
        ) else Box(
            Modifier.size(36.dp).background((if (activity) Burn else Accent).copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    activity -> Icons.AutoMirrored.Filled.DirectionsRun
                    e.type == EntryType.MANUAL -> Icons.Filled.Edit
                    else -> Icons.Filled.Restaurant
                },
                null, tint = if (activity) Burn else Accent, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val (sub, warn) = when {
                e.status == EntryStatus.NEEDS_ANSWER -> "Нужно уточнить" to true
                e.status == EntryStatus.FAILED -> "ИИ не разобрал. Нажмите, чтобы ввести вручную" to true
                k == null && waiting -> "Ждёт ИИ. Нажмите, чтобы ввести ккал вручную" to true
                k == null -> "ИИ считает…" to true
                e.protein != null -> "Б ${e.protein.toInt()} · Ж ${(e.fat ?: 0.0).toInt()} · У ${(e.carbs ?: 0.0).toInt()}" + (if (e.estimated) " · примерно" else "") to false
                e.estimated -> "примерно" to false
                else -> null to false
            }
            if (sub != null) Text(sub, color = if (warn) Warn else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (k == null && e.status != EntryStatus.FAILED && !waiting) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Warn)
        } else {
            Text(
                if (k == null) "—" else if (k < 0) "−${-k}" else "$k",
                color = if (k != null && k < 0) Burn else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(e: Entry, onAnswer: (String) -> Unit, onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = Warn.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(e.rawText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.question.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            val opts = remember(e.options) { parseOptions(e.options) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.forEach { o -> AnswerChip(o, primary = true) { onAnswer(o) } }
                AnswerChip("Не знаю", primary = false) { onAnswer("Не знаю, оцени сам") }
                AnswerChip("Ввести ккал", primary = false, onClick = onOpen)
            }
        }
    }
}

@Composable
private fun AnswerChip(text: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50),
        color = if (primary) Accent.copy(alpha = 0.18f) else Color.Transparent,
        border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text, Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (primary) Accent else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Напишите внизу, что съели или чем занимались: «овсянка с бананом», «падел 1 час», «+300».",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun InputBar(input: String, onInput: (String) -> Unit, onSend: () -> Unit, onQuick: (String) -> Unit, onPhoto: (android.net.Uri) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QuickAdds) { q ->
                    Surface(
                        onClick = { onQuick(q) }, shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(q, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // a photo of the plate: the camera app takes it, the words in the field (if any) go along as a hint
                val camCtx = LocalContext.current
                val shot = remember { Photos.cameraFile(camCtx) }
                val shotUri = remember { runCatching { FileProvider.getUriForFile(camCtx, camCtx.packageName + ".files", shot) }.getOrNull() }
                val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok && shotUri != null) onPhoto(shotUri) }
                IconButton({ shotUri?.let { runCatching { camera.launch(it) } } }) {
                    Icon(Icons.Filled.PhotoCamera, "Сфотографировать еду", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextField(
                    input, onInput, Modifier.weight(1f),
                    placeholder = { Text("Что съели или сделали?") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Accent,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                Spacer(Modifier.width(8.dp))
                // with an empty field the button listens instead: the phone's speech recognition writes the words into
                // the field, to check before sending
                val ctx = LocalContext.current
                val speech = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH) }
                val canListen = remember { speech.resolveActivity(ctx.packageManager) != null }
                val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
                        onInput(if (input.isBlank()) said.replaceFirstChar { it.uppercase() } else "$input $said")
                    }
                }
                val mic = input.isBlank() && canListen
                FilledIconButton(
                    {
                        if (mic) listen.launch(
                            speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Что съели или сделали?")
                        ) else onSend()
                    },
                    Modifier.size(52.dp), enabled = mic || input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color(0xFF00210F)),
                ) {
                    if (mic) Icon(Icons.Filled.Mic, "Сказать голосом") else Icon(Icons.AutoMirrored.Filled.Send, "Добавить")
                }
            }
        }
    }
}

/** Meals by the time they were written, the way calorie apps show a day; workouts go last. */
fun mealGroups(entries: List<Entry>): List<Pair<String, List<Entry>>> {
    val zone = ZoneId.systemDefault()
    fun meal(e: Entry): String {
        if (e.type == EntryType.ACTIVITY || (e.kcal ?: 0) < 0) return "Активность"
        val h = Instant.ofEpochMilli(e.createdAt).atZone(zone).hour
        return when (h) {
            in 4..10 -> "Завтрак"
            in 11..15 -> "Обед"
            in 16..21 -> "Ужин"
            else -> "Перекусы"
        }
    }
    val order = listOf("Завтрак", "Обед", "Ужин", "Перекусы", "Активность")
    // entries waiting for an answer sit in their own card above
    return entries.filter { it.status != EntryStatus.NEEDS_ANSWER }.sortedBy { it.createdAt }.groupBy(::meal).toList().sortedBy { order.indexOf(it.first) }
}

private fun kcalText(k: Int) = if (k < 0) "−${-k} ккал" else "$k ккал"

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

private fun parseOptions(json: String?): List<String> = try {
    val a = org.json.JSONArray(json ?: "[]")
    (0 until a.length()).map { a.getString(it) }
} catch (e: Exception) {
    emptyList()
}

@Composable
fun EntryDialog(
    e: Entry, onDismiss: () -> Unit, onSave: (Int) -> Unit, onRewrite: (String) -> Unit, onDelete: () -> Unit,
) {
    val burn = (e.kcal ?: 0) < 0 || e.type == EntryType.ACTIVITY
    var record by remember { mutableStateOf(e.rawText) }
    var text by remember { mutableStateOf(e.kcal?.let { kotlin.math.abs(it).toString() } ?: "") }
    // a new description means new calories: they are counted again from the text, the number field waits
    val rewritten = record.trim() != e.rawText.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title) },
        text = {
            Column {
                OutlinedTextField(
                    record, { record = it.take(500) },
                    label = { Text(if (burn) "Тренировка" else "Что съели") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { v -> text = v.filter { it.isDigit() }.take(5) },
                    label = { Text(if (burn) "Сожжено, ккал" else "Калории") },
                    supportingText = if (rewritten) ({ Text("Пересчитаю по новому тексту") }) else null,
                    enabled = !rewritten,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    if (e.type == EntryType.FOOD && !rewritten) TextButton({ onRewrite(record) }) { Text("Пересчитать ИИ") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onDelete) { Text("Удалить", color = Over) }
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    if (rewritten) onRewrite(record)
                    else text.toIntOrNull()?.let { onSave(if (burn) -it else it) }
                },
                enabled = if (rewritten) record.isNotBlank() else text.isNotEmpty(),
            ) { Text(if (rewritten) "Пересчитать" else "Сохранить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit) {
    val (text, button) = when (state) {
        is UpdateState.Available -> "Доступна версия ${state.release.versionName}" to "Обновить"
        is UpdateState.ReadyToInstall -> "Версия ${state.release.versionName} скачана" to "Установить"
        is UpdateState.Downloading -> "Загрузка обновления ${state.percent}%" to null
        else -> return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            if (button != null) Button(onUpdate) { Text(button) }
        }
        if (state is UpdateState.Downloading) LinearProgressIndicator(
            progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        val notes = (state as? UpdateState.Available)?.release?.notes.orEmpty()
        if (notes.isNotBlank()) Text(notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
