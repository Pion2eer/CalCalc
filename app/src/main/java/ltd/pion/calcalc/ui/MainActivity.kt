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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
        setContent { CalCalcTheme { Root(vm) } }
    }

    override fun onResume() {
        super.onResume()
        // back from the "allow installs" screen: check again so the banner can install right away
        vm.checkUpdate(silent = true)
    }
}

val Accent = Color(0xFF3DDC84)
val Burn = Color(0xFFFFB74D)
val Warn = Color(0xFFFFD54F)

@Composable
fun CalCalcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF00210F),
            background = Color(0xFF101214),
            surface = Color(0xFF101214),
            surfaceVariant = Color(0xFF1C1F22),
            secondary = Burn,
        ),
        content = content,
    )
}

@Composable
fun Root(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(if (vm.prefs.profileDone) 0 else 2) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Restaurant, null) }, label = { Text("Сегодня") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.TableChart, null) }, label = { Text("Дневник") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Person, null) }, label = { Text("Профиль") })
            }
        },
    ) { pad ->
        // the bottom bar already covers the navigation bar, so the keyboard only adds what it takes beyond it
        val m = Modifier.padding(pad).consumeWindowInsets(pad).imePadding()
        when (tab) {
            0 -> TodayScreen(vm, m)
            1 -> DiaryScreen(vm, m)
            else -> ProfileScreen(vm, m, onSaved = { tab = 0 })
        }
    }
}
