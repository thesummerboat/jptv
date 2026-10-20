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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
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

        Spacer(Modifier.height(8.dp))
        PhoneInputSection(vm, onReceived = { m3u, epg ->
            if (m3u != null) url = m3u
            if (epg != null) epgUrl = epg
        })

        Spacer(Modifier.height(8.dp))
        Text("Live-TV", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.changeStartInLastChannel(!vm.startInLastChannel) }) {
                Text(if (vm.startInLastChannel) "Starta i senaste kanal: PÅ" else "Starta i senaste kanal: AV")
            }
            if (vm.hiddenGroups.isNotEmpty()) {
                OutlinedButton(onClick = { vm.showAllGroups() }) { Text("Visa ${vm.hiddenGroups.size} dolda grupper igen") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Plex", style = MaterialTheme.typography.titleLarge)
        val uri = vm.plex.server?.uri
        Text(
            if (vm.serverName != null && uri != null) "Ansluten till ${vm.serverName} ($uri)" else "Inte ansluten till någon server.",
            color = JptvColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.scanLibrary(null) }, enabled = !vm.scanning && uri != null) {
                Text(if (vm.scanning) "Skannar…" else "Skanna alla bibliotek")
            }
            OutlinedButton(onClick = { vm.connectAndLoad(rediscover = true) }) { Text("Sök server igen") }
            OutlinedButton(onClick = { vm.logout() }) { Text("Logga ut från Plex") }
        }
        vm.scanStatus?.let {
            Text(it, color = if (vm.scanning) JptvColors.Accent else JptvColors.TextDim)
        }

        if (vm.sections.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Bibliotek", style = MaterialTheme.typography.titleLarge)
            Text(
                "★ Favoriter visas först och får flest rader på hemskärmen. Dolda bibliotek syns inte någonstans i appen.",
                color = JptvColors.TextDim,
            )
            vm.sections.forEach { section ->
                val hidden = section.key in vm.hiddenSections
                val favorite = section.key in vm.favoriteSections
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        section.title,
                        color = if (hidden) JptvColors.TextDim else JptvColors.Text,
                        modifier = Modifier.width(260.dp),
                    )
                    OutlinedButton(onClick = { vm.toggleSectionFavorite(section) }, enabled = !hidden) {
                        Text(if (favorite) "★ Favorit" else "☆ Gör till favorit")
                    }
                    OutlinedButton(onClick = { vm.toggleSectionHidden(section) }) {
                        Text(if (hidden) "Dold – visa igen" else "Dölj")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Bild, ljud och betyg", style = MaterialTheme.typography.titleLarge)
        Text(
            "Automatisk bildfrekvens byter TV:ns läge efter filmen (t.ex. 23,976 Hz) och kanalen (50 Hz), så att bilden inte rycker.",
            color = JptvColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.changeAutoFrameRate(!vm.autoFrameRate) }) {
                Text(if (vm.autoFrameRate) "Automatisk bildfrekvens: PÅ" else "Automatisk bildfrekvens: AV")
            }
            OutlinedButton(onClick = { vm.cycleNavSound() }) {
                Text(
                    "Ljud när du bläddrar: " + when (vm.navSound) {
                        1 -> "Lågt"
                        2 -> "Mellan"
                        else -> "Av"
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.cycleTunneling() }) {
                Text(
                    "Tunnlad uppspelning: " + when (vm.tunnelingMode) {
                        1 -> "PÅ"
                        2 -> "AV"
                        else -> if (vm.useTunneling()) "Auto (på)" else "Auto (av)"
                    },
                )
            }
            Text(
                "TV:ns videochip sköter bildtakten. Ger jämnare film på TV med inbyggd app (t.ex. TCL).",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.changeHeroTrailers(!vm.heroTrailers) }) {
                Text(if (vm.heroTrailers) "Trailer på hemskärmen: PÅ" else "Trailer på hemskärmen: AV")
            }
            OutlinedButton(onClick = { vm.cycleThemeLevel() }) {
                Text(
                    "Ledmotiv på seriesidan: " + when (vm.themeLevel) {
                        1 -> "Lågt"
                        2 -> "Mellan"
                        else -> "Av"
                    },
                )
            }
            OutlinedButton(onClick = { vm.changePipEnabled(!vm.pipEnabled) }) {
                Text(if (vm.pipEnabled) "Bild-i-bild för live-TV: PÅ" else "Bild-i-bild för live-TV: AV")
            }
        }
        Text(
            "Bild-i-bild: Tillbaka från en kanal lämnar den i ett hörn medan du bläddrar (samma ström, aldrig två). " +
                "OK på en annan kanal byter i rutan, OK igen (eller ▶‖) = helskärm. Håll inne Tillbaka (eller Meny ≡) stänger rutan.",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        // Felsökning: vad trailern och ledmotivet gjorde senast.
        Text(
            "Senaste trailer: ${vm.trailerStatus ?: "ingen ännu (stå kvar på en titel på hemskärmen i tre sekunder)"}",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Senaste ledmotiv: ${ThemePlayer.status ?: "inget ännu (öppna en serie)"}",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.refreshImdb(force = true) }) { Text("Uppdatera IMDb-betyg") }
            if (vm.notInterested.isNotEmpty()) {
                OutlinedButton(onClick = { vm.clearNotInterested() }) {
                    Text("Glöm ${vm.notInterested.size} \"Inte intresserad\"")
                }
            }
        }
        Text(
            vm.imdbStatus ?: if (vm.imdb.count > 0) {
                "IMDb-betyg för ${vm.imdb.count} titlar, hämtade ${java.text.SimpleDateFormat("d MMM", java.util.Locale("sv", "SE")).format(java.util.Date(vm.imdb.updatedAt))}."
            } else {
                "IMDb-betygen hämtas automatiskt en gång i veckan."
            },
            color = JptvColors.TextDim,
        )

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

/** QR-kod som öppnar en sida på mobilen där adresserna kan klistras in. */
@Composable
private fun PhoneInputSection(vm: AppViewModel, onReceived: (String?, String?) -> Unit) {
    var active by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val server = remember {
        se.jptv.phone.PhoneInputServer(
            currentValues = { vm.prefs.m3uUrl to vm.prefs.epgUrl },
            onSubmit = { m3u, epg ->
                status = "Mottaget från mobilen!"
                onReceived(m3u, epg)
                if (epg != null) vm.prefs.epgUrl = epg.ifBlank { null }
                if (m3u != null) vm.loadPlaylist(m3u) else vm.refreshEpg(force = true)
            },
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { server.stop() }
    }
    Text("Mata in från mobilen", style = MaterialTheme.typography.titleMedium)
    if (!active) {
        OutlinedButton(onClick = {
            address = server.start()
            active = true
            status = if (address == null) "Hittar inget nätverk på TV:n." else null
        }) { Text("Visa QR-kod") }
    } else {
        val addr = address
        if (addr != null) {
            val qr = remember(addr) { se.jptv.phone.PhoneInputServer.qrBitmap(addr).asImageBitmap() }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                androidx.compose.foundation.Image(
                    bitmap = qr,
                    contentDescription = "QR-kod",
                    modifier = Modifier
                        .width(220.dp)
                        .height(220.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Skanna med mobilen (samma Wi-Fi), eller öppna:", color = JptvColors.TextDim)
                    Text(addr, style = MaterialTheme.typography.titleLarge, color = JptvColors.Accent)
                    Text(status ?: "Väntar på mobilen…", color = JptvColors.TextDim)
                    OutlinedButton(onClick = {
                        server.stop()
                        active = false
                    }) { Text("Stäng") }
                }
            }
        } else {
            Text(status ?: "", color = JptvColors.Error)
        }
    }
}
