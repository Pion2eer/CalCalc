package ltd.pion.calcalc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.data.History
import ltd.pion.calcalc.data.HistoryAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale("ru")
private val dateFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", ru)
private val shortDate = DateTimeFormatter.ofPattern("d MMMM", ru)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun HistoryScreen(vm: AppViewModel, onClose: () -> Unit, modifier: Modifier) {
    val list by vm.history.collectAsState()
    val ids by vm.entryIds.collectAsState()
    BackHandler(onBack = onClose)
    HistoryContent(list, ids, LocalDate.now(), vm::restoreFromLog, onClose, modifier)
}

/** Every add, change, AI count, deletion and restore with its time; a deleted entry can be brought back. */
@Composable
fun HistoryContent(
    list: List<History>,
    existing: Set<String>,
    today: LocalDate,
    onRestore: (History) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // only the latest deletion of an entry offers «Восстановить», and only while the entry is gone
    val lastDelete = list.filter { it.action == HistoryAction.DELETE }.groupBy { it.entryId }.mapValues { it.value.first().id }
    val byDate = list.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Text("Журнал записей", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Всё, что добавлялось, менялось и удалялось на этом телефоне, с точным временем. Хранится полгода.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        if (list.isEmpty()) item {
            Text("Пока пусто: журнал начинается с этой версии.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        byDate.forEach { (date, events) ->
            item {
                Text(
                    when (date) {
                        today -> "Сегодня"
                        today.minusDays(1) -> "Вчера"
                        else -> date.format(dateFmt).replaceFirstChar { it.uppercase() }
                    },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            items(events, key = { it.id }) { h ->
                val canRestore = h.action == HistoryAction.DELETE && lastDelete[h.entryId] == h.id && h.entryId !in existing
                Event(h, today, canRestore, { onRestore(h) }, zone)
            }
        }
    }
}

@Composable
private fun Event(h: History, today: LocalDate, canRestore: Boolean, onRestore: () -> Unit, zone: ZoneId) {
    val (label, color) = when (h.action) {
        HistoryAction.ADD -> "Добавлено" to Accent
        HistoryAction.EDIT -> "Изменено" to MaterialTheme.colorScheme.onSurface
        HistoryAction.AI -> "ИИ посчитал" to MaterialTheme.colorScheme.onSurfaceVariant
        HistoryAction.DELETE -> "Удалено" to Danger
        else -> "Восстановлено" to Accent
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Instant.ofEpochMilli(h.at).atZone(zone).toLocalTime().format(timeFmt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  $label", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
                h.kcal?.let { Text(if (it < 0) "−${-it}" else "$it", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            Text(h.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            // the day the entry belongs to, when it is not the day it was changed
            val day = LocalDate.parse(h.day)
            if (day != Instant.ofEpochMilli(h.at).atZone(zone).toLocalDate()) Text(
                "в дневнике за " + if (day == today) "сегодня" else day.format(shortDate),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canRestore) OutlinedButton(onRestore, Modifier.padding(top = 6.dp)) { Text("Восстановить") }
        }
    }
}
