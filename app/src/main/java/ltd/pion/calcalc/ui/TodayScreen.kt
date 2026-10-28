package ltd.pion.calcalc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.EntryStatus
import ltd.pion.calcalc.update.UpdateState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))

@Composable
fun TodayScreen(vm: AppViewModel, modifier: Modifier) {
    val day by vm.day.collectAsState()
    val entries by vm.entries.collectAsState()
    val s by vm.summary.collectAsState()
    val update by vm.update.collectAsState()
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Entry?>(null) }

    Column(modifier.fillMaxSize().imePadding()) {
        UpdateBanner(update, onUpdate = vm::downloadAndInstall)

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.shiftDay(-1) }) { Icon(Icons.Filled.ChevronLeft, "Предыдущий день") }
            Text(
                if (day == LocalDate.now()) "Сегодня" else day.format(dayFmt),
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            )
            if (day != LocalDate.now()) IconButton({ vm.shiftDay(1) }) { Icon(Icons.Filled.ChevronRight, "Следующий день") }
        }

        Ring(s, Modifier.align(Alignment.CenterHorizontally).padding(8.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            if (entries.isEmpty()) item {
                Text(
                    "Напишите, что съели или чем занимались: «овсянка 200 г», «падел 1 час», «+300».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            items(entries, key = { it.id }) { e -> EntryRow(e, onClick = { editing = e }, onDelete = { vm.delete(e) }, onAnswer = { vm.answer(e, it) }) }
        }

        LazyRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Падел 1 час", "Ходьба 30 мин", "Зал 1 час", "+100 ккал")) { chip ->
                AssistChip(onClick = { vm.add(chip) }, label = { Text(chip) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("Что съели или сделали?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.add(input); input = "" }),
            )
            IconButton({ vm.add(input); input = "" }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Добавить")
            }
        }
    }

    editing?.let { e -> KcalDialog(e, onDismiss = { editing = null }, onSave = { vm.setKcal(e, it); editing = null }) }
}

@Composable
private fun Ring(s: DaySummary, modifier: Modifier) {
    val budget = (s.norm + s.burned).coerceAtLeast(1)
    val frac = (s.eaten.toFloat() / budget).coerceIn(0f, 1f)
    val over = s.left < 0
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 16.dp.toPx()
            val box = Size(size.width - w, size.height - w)
            val tl = Offset(w / 2, w / 2)
            drawArc(track, -90f, 360f, false, tl, box, style = Stroke(w))
            drawArc(if (over) Color(0xFFEF5350) else Accent, -90f, 360f * frac, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (over) "Перебор" else "Осталось", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text("${kotlin.math.abs(s.left)}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("ккал", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Съедено", s.eaten, MaterialTheme.colorScheme.onSurface)
        Stat("Сожжено", s.burned, Burn)
        Stat("Норма", s.norm, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (s.pending > 0) Text(
        "Ждут ИИ: ${s.pending}", color = Warn, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun Stat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontWeight = FontWeight.SemiBold, color = color, fontSize = 18.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(e: Entry, onClick: () -> Unit, onDelete: () -> Unit, onAnswer: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge)
                val sub = when {
                    e.status == EntryStatus.NEEDS_ANSWER -> "Нужно уточнить"
                    e.status == EntryStatus.FAILED -> "ИИ не разобрал, введите калории вручную"
                    e.kcal == null -> "Ждёт ИИ, можно ввести калории вручную"
                    e.estimated -> "Примерно"
                    else -> null
                }
                if (sub != null) Text(sub, color = Warn, fontSize = 12.sp)
            }
            val k = e.kcal
            Text(
                when {
                    k == null -> "…"
                    k < 0 -> "−${-k}"
                    else -> "$k"
                },
                color = if (k != null && k < 0) Burn else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onDelete) { Icon(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (e.status == EntryStatus.NEEDS_ANSWER && e.question != null) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Text(e.question, color = Warn)
                val opts = remember(e.options) { parseOptions(e.options) + "Не знаю" }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    opts.forEach { o -> androidx.compose.material3.OutlinedButton({ onAnswer(o) }) { Text(o) } }
                }
            }
        }
    }
}

private fun parseOptions(json: String?): List<String> = try {
    val a = org.json.JSONArray(json ?: "[]")
    (0 until a.length()).map { a.getString(it) }
} catch (e: Exception) {
    emptyList()
}

@Composable
private fun KcalDialog(e: Entry, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val burn = (e.kcal ?: 0) < 0
    var text by remember { mutableStateOf(e.kcal?.let { kotlin.math.abs(it).toString() } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title) },
        text = {
            OutlinedTextField(
                text, { v -> text = v.filter { it.isDigit() }.take(5) },
                label = { Text(if (burn) "Сожжено, ккал" else "Калории") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton({ text.toIntOrNull()?.let { onSave(if (burn) -it else it) } }, enabled = text.isNotEmpty()) { Text("Сохранить") }
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
        Modifier.fillMaxWidth().padding(8.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
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
