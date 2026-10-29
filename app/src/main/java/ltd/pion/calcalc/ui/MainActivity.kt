package ltd.pion.calcalc.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val theme by vm.theme.collectAsState()
            val dark = when (theme) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            // status bar icons follow the app's theme, not the phone's
            DisposableEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                )
                onDispose {}
            }
            CalCalcTheme(dark) { Root(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // back from the "allow installs" screen: check again so the banner can install right away
        vm.checkUpdate(silent = true)
    }
}

val LocalDark = staticCompositionLocalOf { true }

// colours that need to be deeper on a light background to stay readable
val Accent: Color @Composable @ReadOnlyComposable get() = Color(if (LocalDark.current) 0xFF3DDC84 else 0xFF1E9E57)
val Burn: Color @Composable @ReadOnlyComposable get() = Color(if (LocalDark.current) 0xFFFFB74D else 0xFFE07B00)
val Warn: Color @Composable @ReadOnlyComposable get() = Color(if (LocalDark.current) 0xFFFFD54F else 0xFFB07A00)
val Danger: Color @Composable @ReadOnlyComposable get() = Color(if (LocalDark.current) 0xFFEF5350 else 0xFFD32F2F)

@Composable
fun CalCalcTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF3DDC84),
        onPrimary = Color(0xFF00210F),
        background = Color(0xFF101214),
        surface = Color(0xFF101214),
        surfaceVariant = Color(0xFF1C1F22),
        onSurfaceVariant = Color(0xFFA9B0B6),
        secondary = Color(0xFFFFB74D),
        // selected chips and switches: a quiet green instead of Material's default purple
        secondaryContainer = Color(0xFF1E3B2B),
        onSecondaryContainer = Color(0xFFB8F5CF),
        outline = Color(0xFF3A4046),
        outlineVariant = Color(0xFF2A2F33),
        surfaceContainerHigh = Color(0xFF22262A),
        surfaceContainerHighest = Color(0xFF2A2E33),
    ) else lightColorScheme(
        primary = Color(0xFF1E9E57),
        onPrimary = Color.White,
        background = Color(0xFFF3F5F7),
        onBackground = Color(0xFF15181B),
        surface = Color(0xFFF3F5F7),
        onSurface = Color(0xFF15181B),
        // cards are white on a light grey page
        surfaceVariant = Color.White,
        onSurfaceVariant = Color(0xFF5F6870),
        secondary = Color(0xFFE07B00),
        secondaryContainer = Color(0xFFD4F3E0),
        onSecondaryContainer = Color(0xFF0B3D22),
        outline = Color(0xFFC5CBD1),
        outlineVariant = Color(0xFFE1E5E9),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFFFFFFF),
        surfaceContainerHighest = Color(0xFFE9ECEF),
    )
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun Root(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(if (vm.prefs.profileDone) 0 else 2) }
    var log by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0 && !log, { tab = 0; log = false }, { Icon(Icons.Filled.Restaurant, null) }, label = { Text("Сегодня") })
                NavigationBarItem(tab == 1 && !log, { tab = 1; log = false }, { Icon(Icons.Filled.TableChart, null) }, label = { Text("Дневник") })
                NavigationBarItem(tab == 2 && !log, { tab = 2; log = false }, { Icon(Icons.Filled.Person, null) }, label = { Text("Профиль") })
            }
        },
    ) { pad ->
        // the bottom bar already covers the navigation bar, so the keyboard only adds what it takes beyond it
        val m = Modifier.padding(pad).consumeWindowInsets(pad).imePadding()
        when {
            log -> HistoryScreen(vm, onClose = { log = false }, m)
            tab == 0 -> TodayScreen(vm, m)
            tab == 1 -> DiaryScreen(vm, m)
            else -> ProfileScreen(vm, m, onSaved = { tab = 0 }, onOpenLog = { log = true })
        }
    }
}
