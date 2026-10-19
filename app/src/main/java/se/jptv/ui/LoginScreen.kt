package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import se.jptv.AppViewModel

@Composable
fun LoginScreen(vm: AppViewModel) {
    val retryFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("JPTV", style = MaterialTheme.typography.displayMedium, color = JptvColors.Accent)
            Text("Logga in med ditt Plex-konto", style = MaterialTheme.typography.titleLarge)
            val error = vm.loginError
            val code = vm.loginCode
            when {
                error != null -> {
                    Text(error, color = JptvColors.Error, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.beginLogin() }, modifier = Modifier.focusRequester(retryFocus)) {
                        Text("Hämta ny kod")
                    }
                    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
                }
                code == null -> Text("Hämtar kod…", color = JptvColors.TextDim)
                else -> {
                    Text(
                        "Gå till plex.tv/link på mobilen eller datorn och skriv in koden:",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = code,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 16.sp,
                        color = JptvColors.Text,
                        modifier = Modifier
                            .background(JptvColors.Surface, MaterialTheme.shapes.medium)
                            .padding(horizontal = 40.dp, vertical = 16.dp),
                    )
                    Text("Appen fortsätter av sig själv när koden är godkänd.", color = JptvColors.TextDim)
                }
            }
        }
    }
}
