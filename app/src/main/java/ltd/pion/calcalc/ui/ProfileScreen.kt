package ltd.pion.calcalc.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.health.connect.client.PermissionController
import ltd.pion.calcalc.sync.Steps
import ltd.pion.calcalc.sync.StepsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.BuildConfig
import ltd.pion.calcalc.data.Profile
import ltd.pion.calcalc.domain.Norm
import ltd.pion.calcalc.update.UpdateState

@Composable
fun ProfileScreen(vm: AppViewModel, modifier: Modifier, onSaved: () -> Unit) {
    val p0 by vm.profile.collectAsState()
    val w0 by vm.weight.collectAsState()
    val update by vm.update.collectAsState()
    val account by vm.account.collectAsState()
    val authError by vm.authError.collectAsState()
    val ctx = LocalContext.current
    val steps by vm.steps.collectAsState()
    val stepCount by vm.stepCount.collectAsState()
    val stepsLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshSteps()
    }
    LaunchedEffect(Unit) { vm.refreshSteps() }
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onSignInResult(it.data)
    }
    ProfileContent(
        p0 = p0, w0 = w0, update = update, account = account, authError = authError,
        sheetUrl = vm.sheetUrl(), aiUrl = vm.prefs.aiUrl, autoDownload = vm.prefs.autoDownloadOnWifi,
        versionName = BuildConfig.VERSION_NAME,
        onSave = { p, kg -> vm.saveProfile(p, kg); onSaved() },
        onSignIn = { vm.signIn { pi -> signInLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) } },
        onOpenSheet = { u -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) },
        onSync = vm::syncNow, onSignOut = vm::signOut,
        onAiUrl = { vm.prefs.aiUrl = it }, onAutoDownload = { vm.prefs.autoDownloadOnWifi = it },
        onCheckUpdate = { vm.checkUpdate() }, onUpdate = vm::downloadAndInstall, modifier = modifier,
        steps = steps, stepCount = stepCount,
        onSteps = {
            if (steps == StepsState.NEEDS_INSTALL) ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Steps.PROVIDER}&url=healthconnect%3A%2F%2Fonboarding"))
            ) else stepsLauncher.launch(Steps.permissions)
        },
    )
}

@Composable
fun ProfileContent(
    p0: Profile,
    w0: Double,
    update: UpdateState,
    account: String?,
    authError: String?,
    sheetUrl: String?,
    aiUrl: String,
    autoDownload: Boolean,
    versionName: String,
    onSave: (Profile, Double?) -> Unit,
    onSignIn: () -> Unit,
    onOpenSheet: (String) -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onAiUrl: (String) -> Unit,
    onAutoDownload: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    steps: StepsState = StepsState.OFF,
    stepCount: Long? = null,
    onSteps: () -> Unit = {},
) {
    var male by remember(p0) { mutableStateOf(p0.male) }
    var year by remember(p0) { mutableStateOf(p0.birthYear.toString()) }
    var height by remember(p0) { mutableStateOf(p0.heightCm.toString()) }
    var goal by remember(p0) { mutableStateOf(p0.goal) }
    var weight by remember(w0) { mutableStateOf(trimKg(w0)) }
    var url by remember { mutableStateOf(aiUrl) }
    var autoDl by remember { mutableStateOf(autoDownload) }
    var advanced by remember { mutableStateOf(false) }

    val draft = Profile(male, year.toIntOrNull() ?: p0.birthYear, height.toIntOrNull() ?: p0.heightCm, goal)
    val kg = weight.replace(',', '.').toDoubleOrNull()
    val changed = draft != p0 || (kg != null && kg != w0)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Профиль", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Section("О себе") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(male, { male = true }, { Text("Мужчина") })
                FilterChip(!male, { male = false }, { Text("Женщина") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Год рожд.", year, Modifier.weight(1f)) { year = it }
                NumberField("Рост, см", height, Modifier.weight(1f)) { height = it }
                NumberField("Вес, кг", weight, Modifier.weight(1f), decimal = true) { weight = it }
            }
            Text("Цель", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(goal == -1, { goal = -1 }, { Text("Похудеть") })
                FilterChip(goal == 0, { goal = 0 }, { Text("Держать") })
                FilterChip(goal == 1, { goal = 1 }, { Text("Набрать") })
            }
            Row(
                Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Ваша норма", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Norm.daily(draft, kg ?: w0)} ккал в день", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Accent)
                    Text("Тренировки добавляются к ней", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button({ onSave(draft, kg) }, Modifier.fillMaxWidth().height(48.dp), enabled = changed) {
                Text(if (changed) "Сохранить" else "Сохранено")
            }
        }

        Section("Аккаунт Google") {
            if (account == null) {
                Text(
                    "Войдите, чтобы ИИ считал калории еды, а дневник сохранялся в вашу таблицу на Google Диске.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onSignIn, Modifier.fillMaxWidth().height(48.dp)) { Text("Войти через Google") }
            } else {
                Text("Вы вошли как", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(account, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                sheetUrl?.let { u ->
                    OutlinedButton({ onOpenSheet(u) }, Modifier.fillMaxWidth()) { Text("Открыть мою таблицу") }
                }
                OutlinedButton(onSync, Modifier.fillMaxWidth()) { Text("Синхронизировать сейчас") }
                TextButton(onSignOut, Modifier.fillMaxWidth()) { Text("Выйти из аккаунта") }
            }
            authError?.let { Text(it, color = Warn, fontSize = 13.sp) }
        }

        if (steps != StepsState.UNAVAILABLE) Section("Шаги") {
            if (steps == StepsState.ON) {
                Text(
                    "Подключено. Шаги из Google Fit и телефона каждый день попадают в «Сожжено».",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stepCount != null) Text("Сегодня в Health Connect: $stepCount шагов", fontSize = 15.sp)
                if (stepCount == 0L) Text(
                    "Шагов пока нет. Включите в Google Fit: Профиль → настройки → «Синхронизация с Health Connect».",
                    fontSize = 13.sp, color = Warn,
                )
            } else {
                Text(
                    "Калории от шагов будут добавляться в «Сожжено» сами. Шаги берутся из Health Connect, " +
                        "куда их пишут Google Fit, Samsung Health и сам телефон.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onSteps, Modifier.fillMaxWidth().height(48.dp)) {
                    Text(if (steps == StepsState.NEEDS_INSTALL) "Установить Health Connect" else "Подключить шаги")
                }
            }
        }

        Section("Обновления") {
            Text("Установлена версия $versionName", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Скачивать автоматически по Wi-Fi", Modifier.weight(1f), fontSize = 15.sp)
                Switch(autoDl, { autoDl = it; onAutoDownload(it) })
            }
            when (val u = update) {
                is UpdateState.Available, is UpdateState.ReadyToInstall, is UpdateState.Downloading -> UpdateBanner(u, onUpdate)
                else -> {
                    OutlinedButton(onCheckUpdate, Modifier.fillMaxWidth(), enabled = u !is UpdateState.Checking) {
                        Text(if (u is UpdateState.Checking) "Проверяю…" else "Проверить обновления")
                    }
                    when (u) {
                        is UpdateState.UpToDate -> Text("У вас последняя версия", color = Accent, fontSize = 13.sp)
                        is UpdateState.Error -> Text(u.message, color = Warn, fontSize = 13.sp)
                        else -> {}
                    }
                }
            }
        }

        TextButton({ advanced = !advanced }) { Text(if (advanced) "Скрыть дополнительное" else "Дополнительно") }
        if (advanced) OutlinedTextField(
            url, { url = it; onAiUrl(it) }, Modifier.fillMaxWidth(),
            label = { Text("Адрес ИИ-скрипта (…/exec)") }, singleLine = true,
            supportingText = { Text("Обычно уже заполнен, менять не нужно") },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(6)) },
        modifier, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

private fun trimKg(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
