package se.jptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import se.jptv.AppViewModel
import se.jptv.ui.JptvColors
import se.jptv.ui.ignoreUnpairedOk
import se.jptv.ui.requestWhenReady
import se.jptv.web.YtComment
import se.jptv.web.YtJson
import se.jptv.web.YtVideoInfo

/** En rad i panelen: en kommentar (depth 1 = ett svar) eller "Fler kommentarer". */
private sealed class AboutRow(val key: String) {
    class Comment(val c: YtComment, val depth: Int) : AboutRow("c_${depth}_${c.id}")
    object More : AboutRow("more")
}

private val TIME_RE = Regex("""(?<![\d:])((?:\d{1,2}:)?\d{1,2}:\d{2})(?![\d:])""")

/** Första tidsangivelsen i en text ("kolla 4:20"), i ms. */
internal fun firstTimestampMs(text: String): Long? {
    val m = TIME_RE.find(text) ?: return null
    return m.groupValues[1].split(":").fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000
}

/**
 * Om videon och kommentarer, på höger sida i spelaren. ▲/▼ bläddrar, OK på en kommentar visar
 * svaren (eller spolar till tiden den nämner), OK på beskrivningen visar hela.
 */
@Composable
internal fun YtAboutPanel(vm: AppViewModel, v: YtVideoInfo, modifier: Modifier = Modifier, onSeek: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var comments by remember(v.id) { mutableStateOf<List<YtComment>?>(null) }
    var count by remember(v.id) { mutableStateOf<Long?>(null) }
    var next by remember(v.id) { mutableStateOf<String?>(null) }
    var disabled by remember(v.id) { mutableStateOf(false) }
    var error by remember(v.id) { mutableStateOf<String?>(null) }
    var busy by remember(v.id) { mutableStateOf(false) }
    var expanded by remember(v.id) { mutableStateOf(false) }
    val replies = remember(v.id) { mutableStateMapOf<String, List<YtComment>>() }

    LaunchedEffect(v.id) {
        try {
            val page = vm.yt().ytComments(v.id)
            comments = page.comments
            count = page.count
            next = page.next
            disabled = page.disabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Kommentarerna gick inte att hämta"
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val page = vm.yt().ytComments(v.id, token)
                comments = comments.orEmpty() + page.comments
                next = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
            busy = false
        }
    }

    fun toggleReplies(c: YtComment) {
        if (replies.containsKey(c.id)) {
            replies.remove(c.id)
            return
        }
        val token = c.repliesToken ?: return
        scope.launch {
            try {
                replies[c.id] = vm.yt().ytComments(v.id, token).comments
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    val rows = buildList<AboutRow> {
        comments.orEmpty().forEach { c ->
            add(AboutRow.Comment(c, 0))
            replies[c.id]?.forEach { add(AboutRow.Comment(it, 1)) }
        }
        if (next != null) add(AboutRow.More)
    }

    Column(
        modifier
            .ignoreUnpairedOk()
            .fillMaxHeight()
            .width(640.dp)
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(start = 28.dp, top = 28.dp, end = 28.dp),
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "about") {
                val facts = listOfNotNull(
                    v.channel.takeIf { it.isNotBlank() },
                    YtJson.views(v.views),
                    v.published?.let { java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("sv", "SE")).format(java.util.Date(it)) },
                ).joinToString(" · ")
                ListItem(
                    selected = false,
                    onClick = { expanded = !expanded },
                    headlineContent = { Text(v.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            if (facts.isNotBlank()) Text(facts, color = JptvColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (v.description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    v.description,
                                    maxLines = if (expanded) 60 else 5,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!expanded && v.description.length > 200) {
                                    Text("OK: visa hela beskrivningen", color = LocalContentColor.current.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(first),
                )
            }
            item(key = "head") {
                Text(
                    "Kommentarer" + (count?.let { " · " + String.format(java.util.Locale("sv", "SE"), "%,d", it) } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    color = JptvColors.Accent,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
            }
            when {
                error != null && comments == null -> item(key = "error") { Text(error!!, color = JptvColors.TextDim) }
                comments == null -> item(key = "wait") { Text("Hämtar kommentarer…", color = JptvColors.TextDim) }
                comments!!.isEmpty() -> item(key = "none") {
                    Text(if (disabled) "Kommentarerna är avstängda för den här videon." else "Inga kommentarer än.", color = JptvColors.TextDim)
                }
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is AboutRow.Comment -> CommentItem(row.c, row.depth, vm, replies.containsKey(row.c.id)) {
                        val at = firstTimestampMs(row.c.text)
                        when {
                            row.depth == 0 && row.c.repliesToken != null && row.c.replies > 0 -> toggleReplies(row.c)
                            at != null && at < v.durationMs -> onSeek(at)
                        }
                    }
                    AboutRow.More -> ListItem(
                        selected = false,
                        onClick = { loadMore() },
                        headlineContent = { Text(if (busy) "Hämtar…" else "Fler kommentarer", color = JptvColors.Accent) },
                    )
                }
            }
            if (error != null && comments != null) {
                item(key = "error2") { Text(error!!, color = JptvColors.TextDim) }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestWhenReady() }
}

@Composable
private fun CommentItem(c: YtComment, depth: Int, vm: AppViewModel, open: Boolean, onClick: () -> Unit) {
    val meta = listOfNotNull(c.author.takeIf { it.isNotBlank() }, YtJson.ago(c.published)).joinToString(" · ")
    val foot = listOfNotNull(
        c.likes.takeIf { it > 0 }?.let { "👍 " + if (it >= 1000) String.format(java.util.Locale("sv", "SE"), "%.1f tn", it / 1000.0) else "$it" },
        "❤ kanalen gillar".takeIf { c.hearted },
        if (depth == 0 && c.replies > 0) (if (open) "▲ dölj svar" else "▼ ${c.replies} svar (OK)") else null,
    ).joinToString("   ")
    ListItem(
        selected = false,
        onClick = onClick,
        leadingContent = {
            Box(
                Modifier
                    .size(if (depth == 0) 36.dp else 28.dp)
                    .clip(CircleShape)
                    .background(JptvColors.SurfaceHigh),
            ) {
                vm.web.ytImage(c.avatar)?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(if (depth == 0) 36.dp else 28.dp))
                }
            }
        },
        headlineContent = {
            Column {
                val dim = LocalContentColor.current.copy(alpha = 0.7f)
                if (c.pinned) Text("Fäst av kanalen", style = MaterialTheme.typography.labelSmall, color = dim)
                Text(meta, style = MaterialTheme.typography.labelMedium, color = if (c.creator) JptvColors.Accent else dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Column {
                Text(c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
                if (foot.isNotBlank()) Text(foot, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
        },
        modifier = Modifier.padding(start = if (depth == 0) 0.dp else 36.dp),
    )
}
