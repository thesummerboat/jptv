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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import se.jptv.player.PlayerScreen
import se.jptv.ui.DetailsScreen
import se.jptv.ui.EpisodesScreen
import se.jptv.ui.GuideScreen
import se.jptv.ui.HomeScreen
import se.jptv.ui.JptvColors
import se.jptv.ui.JptvTheme
import se.jptv.ui.LiveTvScreen
import se.jptv.ui.LoginScreen
import se.jptv.ui.SearchScreen
import se.jptv.ui.SectionScreen
import se.jptv.ui.SettingsScreen
import se.jptv.ui.ignoreUnpairedOk

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

    // TV:n låter appen leva i dagar i bakgrunden: uppdatera spellista, guide och hemskärm vid återkomst.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.refreshIfStale() }

    Box(
        Modifier
            .fillMaxSize()
            .background(JptvColors.Background),
    ) {
        val screen = vm.current
        // key() ger varje skärm färskt tillstånd när den öppnas.
        key(vm.stack.size, screen) {
            // Ett OK som trycktes ner på förra skärmen ska inte klicka på något här när det släpps.
            Box(Modifier.fillMaxSize().ignoreUnpairedOk()) {
            when (screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm)
                is Screen.Section -> SectionScreen(vm, screen.section)
                is Screen.Episodes -> EpisodesScreen(vm, screen.container)
                is Screen.Details -> DetailsScreen(vm, screen.item)
                Screen.LiveTv -> LiveTvScreen(vm)
                is Screen.Guide -> GuideScreen(vm, screen.group)
                Screen.Search -> SearchScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                is Screen.Player -> PlayerScreen(vm, screen.request)
            }
            }
        }
    }
}
