package se.jptv.plex

class PlexException(message: String) : Exception(message)

data class PlexPin(val id: Long, val code: String)

data class PlexServer(val name: String, val uri: String, val token: String)

data class PlexSection(val key: String, val title: String, val type: String)

data class PlexItem(
    val ratingKey: String,
    val type: String,
    val title: String,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val summary: String?,
    val thumb: String?,
    val art: String?,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val watched: Boolean,
    val partKey: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val resolution: String?,
) {
    val isPlayable: Boolean get() = type == "movie" || type == "episode"

    val isContainer: Boolean get() = type == "show" || type == "season"

    /** Det som visas stort: serienamnet för avsnitt, annars titeln. */
    val displayTitle: String get() = if (type == "episode") showTitle ?: title else title

    val subtitle: String?
        get() = when (type) {
            "episode" -> buildString {
                if (season != null && episode != null) append("S${season}E${episode} · ")
                append(title)
            }
            "season" -> showTitle
            "movie" -> year?.toString()
            else -> null
        }

    val progress: Float
        get() = if (durationMs > 0 && viewOffsetMs > 0) {
            (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f

    val mediaInfo: String
        get() = listOfNotNull(resolution?.let { if (it.all(Char::isDigit)) "${it}p" else it.uppercase() },
            videoCodec?.uppercase(), audioCodec?.uppercase()).joinToString(" · ")
}

data class PlexHomeRow(val title: String, val items: List<PlexItem>)
