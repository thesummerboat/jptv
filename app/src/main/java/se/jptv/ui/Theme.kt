package se.jptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object JptvColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF181D25)
    val SurfaceHigh = Color(0xFF222935)
    val Accent = Color(0xFFF2A541)
    val Text = Color(0xFFECEFF4)
    val TextDim = Color(0xFF9AA4B2)
    val Error = Color(0xFFFF8A80)
}

@Composable
fun JptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JptvColors.Accent,
            onPrimary = Color(0xFF1A1206),
            background = JptvColors.Background,
            onBackground = JptvColors.Text,
            surface = JptvColors.Surface,
            onSurface = JptvColors.Text,
            surfaceVariant = JptvColors.SurfaceHigh,
            onSurfaceVariant = JptvColors.TextDim,
            error = JptvColors.Error,
        ),
    ) {
        // Utan detta blir texten svart utanför ytor som sätter egen textfärg.
        CompositionLocalProvider(LocalContentColor provides JptvColors.Text) {
            content()
        }
    }
}
