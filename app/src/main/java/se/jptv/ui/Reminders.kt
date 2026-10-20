package se.jptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.jptv.AppViewModel
import se.jptv.Reminder

/**
 * Rutan när ett program med påminnelse börjar, var man än är i appen (även mitt i en film).
 * "Byt kanal" startar kanalen i helskärm; Tillbaka eller "Stäng" tar bort rutan. Försvinner efter en minut.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.ReminderPopup(vm: AppViewModel, reminder: Reminder) {
    val focus = remember { FocusRequester() }
    BackHandler { vm.dismissReminder() }
    LaunchedEffect(reminder) {
        focus.requestWhenReady()
        delay(60_000)
        if (vm.dueReminder == reminder) vm.dismissReminder()
    }
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(32.dp)
            .width(440.dp)
            .background(JptvColors.Surface, MaterialTheme.shapes.medium)
            .border(2.dp, JptvColors.Accent, MaterialTheme.shapes.medium)
            .padding(20.dp)
            // Fokus stannar i rutan tills man har valt.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .ignoreUnpairedOk(),
    ) {
        Text("🔔 Påminnelse", color = JptvColors.Accent, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(reminder.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${clock(reminder.start)} på ${reminder.channelName}",
            color = JptvColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.playReminder(reminder) }, modifier = Modifier.focusRequester(focus)) { Text("Byt kanal") }
            OutlinedButton(onClick = { vm.dismissReminder() }) { Text("Stäng") }
        }
    }
}
