package ltd.pion.calcalc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.data.DayTotal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortFmt = DateTimeFormatter.ofPattern("d MMM, EE", Locale("ru"))

@Composable
fun DiaryScreen(vm: AppViewModel, modifier: Modifier) {
    val totals by vm.totals.collectAsState()
    val s by vm.summary.collectAsState()
    DiaryContent(totals, s.norm, LocalDate.now(), modifier)
}

@Composable
fun DiaryContent(totals: List<DayTotal>, norm: Int, today: LocalDate, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("Последние 14 дней", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
            WeekChart(totals, norm, today)
            Text(
                "Столбик: съедено. Пунктир: норма. Оранжевым: сожжено сверх нормы.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Cell("День", 2f, header = true)
                Cell("Съедено", 1f, header = true)
                Cell("Сожжено", 1f, header = true)
                Cell("Баланс", 1f, header = true)
            }
            HorizontalDivider()
        }
        items(totals, key = { it.day }) { t ->
            val balance = t.eaten - t.burned - norm
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Cell(LocalDate.parse(t.day).format(shortFmt), 2f)
                Cell("${t.eaten}", 1f)
                Cell("${t.burned}", 1f)
                Cell(if (balance > 0) "+$balance" else "$balance", 1f)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, header: Boolean = false) {
    Text(
        text, Modifier.weight(weight),
        textAlign = if (weight > 1f) TextAlign.Start else TextAlign.End,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        fontSize = if (header) 12.sp else 14.sp,
    )
}

@Composable
private fun WeekChart(totals: List<DayTotal>, norm: Int, today: LocalDate) {
    val byDay = totals.associateBy { it.day }
    val days = (13 downTo 0).map { today.minusDays(it.toLong()).toString() }
    val maxV = (days.maxOf { (byDay[it]?.eaten ?: 0).coerceAtLeast(norm + (byDay[it]?.burned ?: 0)) }).coerceAtLeast(1) * 1.1f
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val step = size.width / days.size
        val bw = step * 0.6f
        days.forEachIndexed { i, d ->
            val t = byDay[d]
            val x = i * step + (step - bw) / 2
            val eaten = (t?.eaten ?: 0) / maxV * size.height
            drawRect(bar, Offset(x, size.height - eaten), Size(bw, eaten))
            val burned = (t?.burned ?: 0) / maxV * size.height
            val normY = size.height - norm / maxV * size.height
            if (burned > 0) drawRect(Burn.copy(alpha = 0.5f), Offset(x, normY - burned), Size(bw, burned))
        }
        val y = size.height - norm / maxV * size.height
        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
    }
}
