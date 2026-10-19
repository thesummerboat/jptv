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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    override fun onStop() {
        super.onStop()
        // Appen lämnas: släpp filmens bildfrekvens, så att menyerna startar i TV:ns vanliga läge nästa gång.
        se.jptv.player.DisplayModes.resetNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        se.jptv.player.DisplayModes.attach(this)
        se.jptv.ui.NavSounds.init(this)
        setContent {
            JptvTheme {
                JptvApp()
            }
        }
    }
}

private fun navSound(ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val native = ev.nativeKeyEvent
    when (native.keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
            if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                se.jptv.ui.NavSounds.move(
                    when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> se.jptv.ui.NavSounds.UP
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> se.jptv.ui.NavSounds.DOWN
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> se.jptv.ui.NavSounds.LEFT
                        else -> se.jptv.ui.NavSounds.RIGHT
                    },
                )
            }
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER ->
            if (native.action == android.view.KeyEvent.ACTION_UP) se.jptv.ui.NavSounds.select()
    }
    return false
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
            Box(
                Modifier
                    .fillMaxSize()
                    .ignoreUnpairedOk()
                    // Diskret klick när man bläddrar; aldrig i spelaren.
                    .then(if (screen is Screen.Player) Modifier else Modifier.onPreviewKeyEvent { ev -> navSound(ev) }),
            ) {
            when (screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Home -> HomeScreen(vm)
                is Screen.Section -> SectionScreen(vm, screen.section, screen.genre)
                is Screen.Collection -> se.jptv.ui.CollectionScreen(vm, screen.collection)
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
