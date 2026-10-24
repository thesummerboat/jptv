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
import se.jptv.player.PipWindow
import se.jptv.player.PlayerScreen
import se.jptv.ui.DetailsScreen
import se.jptv.ui.EpisodesScreen
import se.jptv.ui.GuideScreen
import se.jptv.ui.HomeScreen
import se.jptv.ui.JptvColors
import se.jptv.ui.JptvTheme
import se.jptv.ui.LiveTvScreen
import se.jptv.ui.LoginScreen
import se.jptv.ui.ReminderPopup
import se.jptv.ui.SearchScreen
import se.jptv.ui.SectionScreen
import se.jptv.ui.SettingsScreen
import se.jptv.ui.ignoreUnpairedOk

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        // Appen lämnas: släpp filmens bildfrekvens, så att menyerna startar i TV:ns vanliga läge nästa gång.
        se.jptv.player.DisplayModes.resetNow()
        se.jptv.ui.ThemePlayer.stop(fade = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Samma app på TV och telefon: TV:n får fjärrkontrollsgränssnittet, telefonen pekgränssnittet.
        val uiMode = getSystemService(android.app.UiModeManager::class.java)
        val isTv = uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (isTv) {
            se.jptv.player.DisplayModes.attach(this)
            se.jptv.ui.NavSounds.init(this)
        } else {
            // Telefonen visar statusfältet utom i spelaren.
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        setContent {
            if (isTv) {
                JptvTheme {
                    JptvApp()
                }
            } else {
                se.jptv.mobile.MobileApp()
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

/** Släppet av en långtryckt Tillbaka ska inte också gå tillbaka en skärm. */
private var swallowBackUp = false

/**
 * Bild-i-bild: ▶‖ tar kanalen till helskärm. Håll inne Tillbaka, tryck Meny (≡) eller Stopp för att
 * stänga rutan var man än är; ett vanligt kort Tillbaka går tillbaka som vanligt.
 */
private fun pipKey(vm: AppViewModel, ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val native = ev.nativeKeyEvent
    if (native.keyCode == android.view.KeyEvent.KEYCODE_BACK && swallowBackUp) {
        if (native.action == android.view.KeyEvent.ACTION_UP) swallowBackUp = false
        return true
    }
    if (vm.pip == null || vm.current is Screen.Player) return false
    val down = native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0
    return when (native.keyCode) {
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
            if (down) vm.resumePip()
            true
        }
        android.view.KeyEvent.KEYCODE_MEDIA_STOP, android.view.KeyEvent.KEYCODE_MENU -> {
            if (down) vm.closePip()
            true
        }
        android.view.KeyEvent.KEYCODE_BACK -> {
            // Håll inne: stäng rutan. Första trycket släpps igenom (vanlig Tillbaka när man släpper).
            if (native.action == android.view.KeyEvent.ACTION_DOWN && (native.repeatCount > 0 || native.isLongPress)) {
                vm.closePip()
                swallowBackUp = true
                true
            } else {
                false
            }
        }
        else -> false
    }
}

@Composable
fun JptvApp(vm: AppViewModel = viewModel()) {
    // Tillbaka-knappen på fjärren går ett steg bakåt; på första skärmen stängs först bild-i-bild,
    // sedan appen som vanligt.
    BackHandler(enabled = vm.stack.size > 1 || vm.pip != null) {
        if (!vm.back()) vm.closePip()
    }

    // TV:n låter appen leva i dagar i bakgrunden: uppdatera spellista, guide och hemskärm vid återkomst.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.refreshIfStale() }
    // Appen lämnas: bild-i-bild stängs, så att strömmen inte tar en anslutning i onödan.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.closePip()
        vm.onBackground()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(JptvColors.Background)
            // Medan TV:n byter frekvens är skärmen svart; fjärren väntar så länge.
            .onPreviewKeyEvent { ev -> se.jptv.player.DisplayModes.blackout || pipKey(vm, ev) },
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
                Screen.YouTube -> se.jptv.ui.YouTubeScreen(vm)
                is Screen.YouTubeChannel -> se.jptv.ui.YouTubeChannelScreen(vm, screen.id, screen.name)
                Screen.YouTubeSearch -> se.jptv.ui.YouTubeSearchScreen(vm)
            }
            }
        }
        val pip = vm.pip
        if (pip != null && screen !is Screen.Player) PipWindow(vm, pip)
        // Påminnelse från guiden: programmet har börjat.
        vm.dueReminder?.let { reminder -> ReminderPopup(vm, reminder) }
        if (se.jptv.player.DisplayModes.blackout) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        }
    }
}
