package se.jptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import se.jptv.player.PlayerScreen
import se.jptv.ui.EpisodesScreen
import se.jptv.ui.HomeScreen
import se.jptv.ui.JptvColors
import se.jptv.ui.JptvTheme
import se.jptv.ui.LiveTvScreen
import se.jptv.ui.LoginScreen
import se.jptv.ui.SectionScreen
import se.jptv.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JptvTheme {
                JptvApp()
            }
        }
    }
}

@Composable
fun JptvApp(vm: AppViewModel = viewModel()) {
    // Tillbaka-knappen på fjärren går ett steg bakåt; på första skärmen stängs appen som vanligt.
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    Box(
        Modifier
            .fillMaxSize()
            .background(JptvColors.Background),
    ) {
        val screen = vm.current
        // key() ger varje skärm färskt tillstånd när den öppnas.
        key(vm.stack.size, screen) {
            when (screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm)
                is Screen.Section -> SectionScreen(vm, screen.section)
                is Screen.Episodes -> EpisodesScreen(vm, screen.container)
                Screen.LiveTv -> LiveTvScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                is Screen.Player -> PlayerScreen(vm, screen.request)
            }
        }
    }
}
