package ltd.pion.calcalc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.data.DayTotal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ru = Locale("ru")
private val longFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", ru)
private val Good = Color(0xFF3DDC84)
private val Bad = Color(0xFFEF5350)

@Composable
fun DiaryScreen(vm: AppViewModel, modifier: Modifier) {
    val totals by vm.totals.collectAsState()
    val s by vm.summary.collectAsState()
    DiaryContent(totals, s.norm, LocalDate.now(), modifier)
}

@Composable
fun DiaryContent(totals: List<DayTotal>, norm: Int, today: LocalDate, modifier: Modifier = Modifier) {
    val byDay = totals.associateBy { it.day }
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val logged = week.mapNotNull { byDay[it.toString()] }.filter { it.eaten > 0 }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Дневник", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }

        item {
            Card {
                Text("Последние 7 дней", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (logged.isEmpty()) {
                    Text(
                        "Здесь появится неделя, когда вы начнёте записывать еду.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                    )
                } else {
                    // balance: what was eaten minus the norm and the workouts; below zero is a deficit
                    val balance = logged.sumOf { it.eaten - it.burned - norm }
                    val avg = logged.sumOf { it.eaten - it.burned } / logged.size
                    val inNorm = logged.count { it.eaten - it.burned <= norm }
                    Row(Modifier.fillMaxWidth()) {
                        Figure("$avg", "ккал в день\nв среднем", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        Figure("$inNorm из ${logged.size}", "дней\nв норме", Good, Modifier.weight(1f))
                        Figure(signed(balance), "ккал баланс\n≈ ${kg(balance)} кг", if (balance <= 0) Good else Bad, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                WeekChart(week, byDay, norm, today)
            }
        }

        val days = totals.filter { it.eaten > 0 || it.burned > 0 }.sortedByDescending { it.day }
        if (days.isNotEmpty()) item {
            Text(
                "По дням", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
        if (days.isNotEmpty()) item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
                Column {
                    days.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.background)
                        DayRow(t, norm, today)
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun Figure(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeekChart(week: List<LocalDate>, byDay: Map<String, DayTotal>, norm: Int, today: LocalDate) {
    val values = week.map { d -> byDay[d.toString()]?.let { it.eaten - it.burned } ?: 0 }
    val maxV = (values.maxOrNull() ?: 0).coerceAtLeast(norm) * 1.15f
    val track = MaterialTheme.colorScheme.background
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val step = size.width / week.size
        val bw = step * 0.56f
        val r = CornerRadius(bw / 2.5f)
        values.forEachIndexed { i, v ->
            val x = i * step + (step - bw) / 2
            drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), r)
            if (v > 0) {
                val h = v / maxV * size.height
                drawRoundRect(if (v <= norm) Good else Bad, Offset(x, size.height - h), Size(bw, h), r)
            }
        }
        val y = size.height - norm / maxV * size.height
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        week.forEach { d ->
            Text(
                d.dayOfWeek.getDisplayName(TextStyle.SHORT, ru).replaceFirstChar { it.uppercase() },
                Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                color = if (d == today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        "Пунктир: ваша норма ($norm ккал). Тренировки уже вычтены.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DayRow(t: DayTotal, norm: Int, today: LocalDate) {
    val d = LocalDate.parse(t.day)
    val balance = t.eaten - t.burned - norm
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when (d) {
                    today -> "Сегодня"
                    today.minusDays(1) -> "Вчера"
                    else -> d.format(longFmt).replaceFirstChar { it.uppercase() }
                },
                fontSize = 15.sp,
            )
            Text(
                "Съедено ${t.eaten}" + if (t.burned > 0) " · сожжено ${t.burned}" else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            signed(balance), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = if (balance <= 0) Good else Bad,
            modifier = Modifier
                .background((if (balance <= 0) Good else Bad).copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0"

// about 7700 kcal per kilogram of body fat
private fun kg(balance: Int): String {
    val v = balance / 7700.0
    val s = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(v))
    return if (v < -0.05) "−$s" else if (v > 0.05) "+$s" else "0"
}
