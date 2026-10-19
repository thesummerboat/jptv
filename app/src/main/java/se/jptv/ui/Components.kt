@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage

val PosterWidth: Dp = 150.dp

/** Affischkort som skalar upp och får ram när det har fokus (inbyggt i Compose for TV). */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    progress: Float,
    watched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(PosterWidth)) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .width(PosterWidth)
                .aspectRatio(2f / 3f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(JptvColors.SurfaceHigh),
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JptvColors.TextDim,
                )
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(JptvColors.Background.copy(alpha = 0.7f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(5.dp)
                                .background(JptvColors.Accent),
                        )
                    }
                }
                if (watched && progress == 0f) {
                    Text(
                        text = "✓",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(JptvColors.Accent, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        color = JptvColors.Background,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = JptvColors.TextDim,
            )
        }
    }
}

/** Centrerat meddelande med valfri knapp, för tomma lägen och fel. */
@Composable
fun MessageBox(
    message: String,
    modifier: Modifier = Modifier,
    buttonText: String? = null,
    onButton: (() -> Unit)? = null,
    buttonModifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(560.dp),
            )
            if (buttonText != null && onButton != null) {
                Button(onClick = onButton, modifier = buttonModifier) { Text(buttonText) }
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Spacer(Modifier.width(16.dp))
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = JptvColors.TextDim)
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
