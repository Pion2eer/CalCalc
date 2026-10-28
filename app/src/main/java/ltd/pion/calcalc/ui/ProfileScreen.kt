package ltd.pion.calcalc.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
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
) {
    var male by remember(p0) { mutableStateOf(p0.male) }
    var year by remember(p0) { mutableStateOf(p0.birthYear.toString()) }
    var height by remember(p0) { mutableStateOf(p0.heightCm.toString()) }
    var goal by remember(p0) { mutableStateOf(p0.goal) }
    var weight by remember(w0) { mutableStateOf(trimKg(w0)) }
    var url by remember { mutableStateOf(aiUrl) }
    var autoDl by remember { mutableStateOf(autoDownload) }

    val draft = Profile(male, year.toIntOrNull() ?: p0.birthYear, height.toIntOrNull() ?: p0.heightCm, goal)
    val kg = weight.replace(',', '.').toDoubleOrNull()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Профиль", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(male, { male = true }, { Text("Мужчина") })
            FilterChip(!male, { male = false }, { Text("Женщина") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Год рождения", year, Modifier.weight(1f)) { year = it }
            NumberField("Рост, см", height, Modifier.weight(1f)) { height = it }
            NumberField("Вес, кг", weight, Modifier.weight(1f), decimal = true) { weight = it }
        }
        Text("Цель")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(goal == -1, { goal = -1 }, { Text("Похудеть") })
            FilterChip(goal == 0, { goal = 0 }, { Text("Держать") })
            FilterChip(goal == 1, { goal = 1 }, { Text("Набрать") })
        }
        Text(
            "Норма: ${Norm.daily(draft, kg ?: w0)} ккал в день, тренировки добавляются к ней.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button({ onSave(draft, kg) }, Modifier.fillMaxWidth()) { Text("Сохранить") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Аккаунт Google", style = MaterialTheme.typography.titleMedium)
        if (account == null) {
            Text(
                "Войдите, чтобы ИИ считал калории еды, а дневник сохранялся в вашу таблицу на Google Диске.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onSignIn, Modifier.fillMaxWidth()) { Text("Войти через Google") }
        } else {
            Text("Вы вошли как $account")
            sheetUrl?.let { u ->
                OutlinedButton({ onOpenSheet(u) }, Modifier.fillMaxWidth()) {
                    Text("Открыть мою таблицу")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onSync, Modifier.weight(1f)) { Text("Синхронизировать") }
                OutlinedButton(onSignOut, Modifier.weight(1f)) { Text("Выйти") }
            }
        }
        authError?.let { Text(it, color = Warn) }
        OutlinedTextField(
            url, { url = it; onAiUrl(it) }, Modifier.fillMaxWidth(),
            label = { Text("Адрес ИИ-скрипта (…/exec)") }, singleLine = true,
            supportingText = { Text("Обычно уже заполнен, менять не нужно") },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Обновления", style = MaterialTheme.typography.titleMedium)
        Text("Установлена версия $versionName", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скачивать автоматически по Wi-Fi", Modifier.weight(1f))
            Switch(autoDl, { autoDl = it; onAutoDownload(it) })
        }
        when (val u = update) {
            is UpdateState.Available, is UpdateState.ReadyToInstall, is UpdateState.Downloading -> UpdateBanner(u, onUpdate)
            else -> {
                Button(onCheckUpdate, Modifier.fillMaxWidth(), enabled = u !is UpdateState.Checking) {
                    Text(if (u is UpdateState.Checking) "Проверяю…" else "Проверить обновления")
                }
                when (u) {
                    is UpdateState.UpToDate -> Text("У вас последняя версия", color = Accent)
                    is UpdateState.Error -> Text(u.message, color = Warn)
                    else -> {}
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
