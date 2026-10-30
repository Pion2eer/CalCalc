package ltd.pion.calcalc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.data.Health
import ltd.pion.calcalc.data.Profile
import ltd.pion.calcalc.domain.Norm

@Composable
fun SurveyScreen(vm: AppViewModel, onClose: () -> Unit, modifier: Modifier) {
    val p by vm.profile.collectAsState()
    val w by vm.weight.collectAsState()
    BackHandler(onBack = onClose)
    SurveyContent(p, w, onSave = { vm.saveProfile(it, null); onClose() }, onClose = onClose, modifier = modifier)
}

private enum class Step { ACTIVITY, TRAINING, HEALTH, PREGNANCY, DIET, RESULT }

/** «Уточнить норму КБЖУ»: a few questions, one per page, then the norm they give. */
@Composable
fun SurveyContent(
    p0: Profile,
    weightKg: Double,
    onSave: (Profile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    startStep: Int = 0,
) {
    var p by remember(p0) { mutableStateOf(p0) }
    val steps = Step.entries.filter { it != Step.PREGNANCY || !p.male }
    var i by remember { mutableIntStateOf(startStep.coerceIn(0, steps.size - 1)) }
    val step = steps[i.coerceAtMost(steps.size - 1)]
    val back: () -> Unit = { if (i == 0) onClose() else i -= 1 }
    BackHandler(onBack = back)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Норма КБЖУ", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${i + 1} из ${steps.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            { (i + 1f) / steps.size }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            color = Accent, trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (step) {
                Step.ACTIVITY -> {
                    Question("Как проходит обычный день?", "Без тренировок: их приложение добавит само, когда вы их запишете")
                    listOf(
                        "Сижу почти весь день" to "Офис, учёба, за рулём",
                        "Много хожу, на ногах" to "Продавец, учитель, курьер пешком",
                        "Физическая работа" to "Стройка, склад, грузчик",
                    ).forEachIndexed { n, (t, d) -> Choice(t, d, p.activity == n) { p = p.copy(activity = n) } }
                }
                Step.TRAINING -> {
                    Question("Тренируетесь регулярно?", "От тренировок зависит, сколько нужно белка")
                    listOf(
                        "Нет или редко" to null,
                        "Силовые, зал" to "Мышцам нужно больше белка",
                        "Кардио: бег, вело, игры" to "Падел, теннис, плавание",
                        "И то, и другое" to null,
                    ).forEachIndexed { n, (t, d) -> Choice(t, d, p.training == n) { p = p.copy(training = n) } }
                }
                Step.HEALTH -> {
                    Question("Есть что-то из этого?", "Можно выбрать несколько")
                    listOf(
                        Health.DIABETES2 to ("Диабет 2 типа или преддиабет" to "Меньше быстрых углеводов"),
                        Health.DIABETES1 to ("Диабет 1 типа" to "Углеводы под контролем"),
                        Health.KIDNEYS to ("Болезни почек" to "Белок ограничен"),
                        Health.CHOLESTEROL to ("Повышенный холестерин" to "Меньше жиров"),
                        Health.GOUT to ("Подагра" to "Белок без излишка"),
                    ).forEach { (k, td) ->
                        Choice(td.first, td.second, k in p.health, multi = true) {
                            p = p.copy(health = if (k in p.health) p.health - k else p.health + k)
                        }
                    }
                    Choice("Ничего из этого", null, p.health.isEmpty(), multi = true) { p = p.copy(health = emptySet()) }
                }
                Step.PREGNANCY -> {
                    Question("Беременность или кормление грудью?", "В это время худеть не стоит, норма будет выше")
                    listOf("Нет", "Беременна", "Кормлю грудью")
                        .forEachIndexed { n, t -> Choice(t, null, p.pregnancy == n) { p = p.copy(pregnancy = n) } }
                }
                Step.DIET -> {
                    Question("Как вам удобнее питаться?", null)
                    listOf(
                        "Обычно" to "Сбалансированно, без перекосов",
                        "Меньше углеводов" to "Больше жиров и белка вместо хлеба и сладкого",
                        "Больше белка" to "Мясо, рыба, творог, протеин",
                    ).forEachIndexed { n, (t, d) -> Choice(t, d, p.diet == n) { p = p.copy(diet = n) } }
                }
                Step.RESULT -> Result(p, weightKg)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (i > 0) OutlinedButton(back, Modifier.weight(1f).height(52.dp)) { Text("Назад") }
            if (step == Step.RESULT) Button({ onSave(p.copy(surveyDone = true)) }, Modifier.weight(1f).height(52.dp)) { Text("Сохранить норму") }
            else Button({ i++ }, Modifier.weight(1f).height(52.dp)) { Text("Далее") }
        }
    }
}

@Composable
private fun Question(title: String, hint: String?) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    hint?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Choice(title: String, hint: String?, selected: Boolean, multi: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = if (selected) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (multi) Checkbox(selected, { onClick() }) else RadioButton(selected, onClick)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                hint?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Result(p: Profile, weightKg: Double) {
    val kcal = Norm.daily(p, weightKg)
    val m = Norm.macros(p, weightKg, kcal)
    Question("Ваша норма", "Тренировки и шаги добавляются к ней каждый день")
    Surface(shape = RoundedCornerShape(24.dp), color = HeroBg, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$kcal", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("ккал в день", fontSize = 14.sp, color = HeroMuted)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                MacroGoal("Белки", m.protein, Color(0xFF64B5F6), Modifier.weight(1f))
                MacroGoal("Жиры", m.fat, Color(0xFFF06292), Modifier.weight(1f))
                MacroGoal("Углеводы", m.carbs, Color(0xFFFFD54F), Modifier.weight(1f))
            }
        }
    }
    val notes = buildList {
        if (p.training == 1 || p.training == 3) add("Белка больше: он нужен мышцам после силовых.")
        if (Health.KIDNEYS in p.health) add("Белок ограничен 0,8 г на кг веса, чтобы беречь почки.")
        if (Health.DIABETES1 in p.health || Health.DIABETES2 in p.health) add("Углеводов не больше 40 %: выбирайте медленные (крупы, овощи) вместо сладкого.")
        if (Health.CHOLESTEROL in p.health) add("Жиров меньше, лучше растительные и рыба.")
        if (!p.male && p.pregnancy > 0) add("Дефицита нет: в это время калории нужны малышу.")
    }
    notes.forEach { Text("• $it", fontSize = 14.sp) }
    if (p.health.isNotEmpty() || (!p.male && p.pregnancy > 0)) Text(
        "Это ориентир, а не назначение. При заболеваниях и беременности норму лучше согласовать с врачом.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MacroGoal(name: String, grams: Int, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$grams г", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(name, fontSize = 13.sp, color = HeroMuted)
    }
}
