package se.jptv.plex

class PlexException(message: String) : Exception(message)

data class PlexPin(val id: Long, val code: String)

data class PlexServer(val name: String, val uri: String, val token: String)

data class PlexSection(val key: String, val title: String, val type: String)

/** Ett spår i filen. type: 1 = video, 2 = ljud, 3 = undertext. */
data class PlexStream(
    val id: Long,
    val type: Int,
    val codec: String?,
    val languageCode: String?,
    val title: String,
    val selected: Boolean,
    /** Satt för externa undertexter (t.ex. en .srt bredvid filen) som hämtas separat. */
    val key: String?,
) {
    val isExternalSubtitle: Boolean get() = type == 3 && key != null
}

/** En träff från Plex undertextsökning (OpenSubtitles m.fl.). */
data class SubtitleResult(
    val key: String,
    val title: String,
    val languageCode: String?,
    val provider: String?,
    val score: String?,
    val hearingImpaired: Boolean,
    val codec: String?,
)

data class PlexSearchHub(val title: String, val items: List<PlexItem>)

enum class LibrarySort(val label: String, val param: String) {
    TITLE("A–Ö", "titleSort"),
    ADDED("Nyligen tillagt", "addedAt:desc"),
    YEAR("År", "year:desc"),
    RATING("Betyg", "audienceRating:desc"),
}

/** Intro- och eftertextsmarkörer som Plex-servern har hittat. */
data class PlexMarker(val type: String, val startMs: Long, val endMs: Long, val final: Boolean = false)

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
    /** Seriens ratingKey för avsnitt och säsonger. */
    val showKey: String? = null,
    val streams: List<PlexStream> = emptyList(),
    val markers: List<PlexMarker> = emptyList(),
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    val childCount: Int? = null,
    /** Filens id i Plex, behövs för att spara valt ljud- och undertextspår. */
    val partId: Long? = null,
    /** Antal sedda avsnitt i en säsong/serie. */
    val viewedLeafCount: Int? = null,
) {
    val hasUnwatched: Boolean get() = (childCount ?: 0) > (viewedLeafCount ?: 0)

    val intro: PlexMarker? get() = markers.firstOrNull { it.type == "intro" }

    /**
     * Eftertexterna i slutet. Plex kan ge flera "credits" (t.ex. en sång mitt i avsnittet);
     * den sista är märkt final, annars tas den som börjar senast.
     */
    val credits: PlexMarker?
        get() = markers.filter { it.type == "credits" }.let { list -> list.firstOrNull { it.final } ?: list.maxByOrNull { it.startMs } }

    /** En minimal serie att öppna avsnittslistan med, från ett avsnitt eller en säsong. */
    fun showContainer(): PlexItem? {
        val key = showKey ?: return null
        return copy(
            ratingKey = key, type = "show", title = showTitle ?: title, showTitle = null,
            season = null, episode = null, summary = null, thumb = thumb, partKey = null,
            streams = emptyList(), markers = emptyList(), viewOffsetMs = 0, durationMs = 0,
        )
    }

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
