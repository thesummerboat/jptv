package se.jptv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import se.jptv.AppViewModel
import se.jptv.BuildConfig
import se.jptv.iptv.IptvUrls

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf(vm.prefs.m3uUrl ?: "") }
    var epgUrl by remember { mutableStateOf(vm.prefs.epgUrl ?: "") }
    val fieldFocus = remember { FocusRequester() }
    val loadFocus = remember { FocusRequester() }
    // Tomt fält: börja i fältet. Annars på knappen, så att tangentbordet inte dyker upp i onödan.
    val startInField = remember { url.isBlank() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 48.dp, top = 28.dp, end = 48.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Inställningar")
        Spacer(Modifier.height(8.dp))

        Text("IPTV", style = MaterialTheme.typography.titleLarge)
        Text(
            "Adress till din M3U-lista. Fungerar även med Xtream-länkar (get.php?…&type=m3u_plus).",
            color = JptvColors.TextDim,
        )
        UrlField(
            value = url,
            onValueChange = { url = it },
            onDone = { vm.loadPlaylist(url) },
            modifier = Modifier.focusRequester(fieldFocus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.loadPlaylist(url) },
                enabled = !vm.iptvLoading,
                modifier = Modifier.focusRequester(loadFocus),
            ) {
                Text(if (vm.iptvLoading) "Laddar…" else "Ladda spellista")
            }
            if (vm.playlist != null) {
                OutlinedButton(onClick = { vm.removePlaylist(); url = "" }) { Text("Ta bort spellista") }
            }
        }
        val playlist = vm.playlist
        Text(
            vm.iptvStatus ?: if (playlist != null) "${playlist.channels.size} kanaler i ${playlist.groups.size} grupper" else "Ingen spellista laddad.",
            color = JptvColors.TextDim,
        )

        Spacer(Modifier.height(8.dp))
        Text("TV-guide (XMLTV)", style = MaterialTheme.typography.titleMedium)
        val guessed = remember(vm.playlist) { IptvUrls.guessEpgUrl(vm.prefs.m3uUrl, vm.playlist) }
        Text(
            if (guessed != null) {
                "Lämna tomt för att använda guiden från din leverantör (${guessed.toHttpUrlOrNull()?.host ?: "hittad automatiskt"})."
            } else {
                "Klistra in adressen till leverantörens XMLTV-guide (.xml eller .xml.gz)."
            },
            color = JptvColors.TextDim,
        )
        UrlField(value = epgUrl, onValueChange = { epgUrl = it }, onDone = { vm.setEpgUrl(epgUrl) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.setEpgUrl(epgUrl) }, enabled = !vm.epgLoading && vm.playlist != null) {
                Text(if (vm.epgLoading) "Hämtar…" else "Spara och hämta guide")
            }
        }
        val epg = vm.epg
        Text(
            vm.epgStatus ?: if (epg != null) "Guide för ${epg.channelCount} kanaler, hämtad ${clock(epg.loadedAt)}." else "Ingen guide laddad.",
            color = JptvColors.TextDim,
        )

        Spacer(Modifier.height(16.dp))
        Text("Plex", style = MaterialTheme.typography.titleLarge)
        val uri = vm.plex.server?.uri
        Text(
            if (vm.serverName != null && uri != null) "Ansluten till ${vm.serverName} ($uri)" else "Inte ansluten till någon server.",
            color = JptvColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.connectAndLoad(rediscover = true) }) { Text("Sök server igen") }
            OutlinedButton(onClick = { vm.logout() }) { Text("Logga ut från Plex") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Uppdateringar", style = MaterialTheme.typography.titleLarge)
        Text(
            vm.updateMessage ?: "Installerad version: ${BuildConfig.VERSION_NAME}. Appen söker själv efter nya versioner.",
            color = JptvColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }) { Text("Sök efter uppdatering") }
            vm.availableUpdate?.let { release ->
                Button(onClick = { vm.installUpdate() }, enabled = !vm.updateBusy) {
                    Text("Installera ${release.versionName}")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("JPTV ${BuildConfig.VERSION_NAME}", color = JptvColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
    LaunchedEffect(Unit) {
        if (startInField) fieldFocus.requestWhenReady() else loadFocus.requestWhenReady()
    }
}

@Composable
private fun UrlField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = JptvColors.Text, fontSize = 20.sp),
        cursorBrush = SolidColor(JptvColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .width(900.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = 2.dp,
                color = if (focused) JptvColors.Accent else JptvColors.SurfaceHigh,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
