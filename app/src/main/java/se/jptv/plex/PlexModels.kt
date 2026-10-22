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
    /** Video: bildfrekvens, t.ex. 23.976. */
    val frameRate: Float? = null,
    /** Video: Dolby Vision-profil om filen har Dolby Vision. */
    val doviProfile: Int? = null,
    val doviPresent: Boolean = false,
    /** Video: överföringsfunktion, "smpte2084" = HDR10/PQ, "arib-std-b67" = HLG. */
    val colorTrc: String? = null,
    val height: Int? = null,
    /** Ljud: antal kanaler (6 = 5.1, 8 = 7.1). */
    val channels: Int? = null,
) {
    val isExternalSubtitle: Boolean get() = type == 3 && key != null

    val isAtmos: Boolean get() = type == 2 && title.contains("Atmos", ignoreCase = true)
}

/** Betyg från t.ex. IMDb eller Rotten Tomatoes, som Plex har hämtat. */
data class PlexRating(val source: String, val text: String, val value: Double = 0.0)

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

/** Sortering. IMDB sorteras i appen efter IMDb-betyget, de andra av servern. */
enum class LibrarySort(val label: String, val param: String) {
    TITLE("A–Ö", "titleSort"),
    ADDED("Nytt", "addedAt:desc"),
    YEAR("År", "year:desc"),
    IMDB("IMDb", "titleSort"),
}

/** En genre (eller annat filter) i ett bibliotek. id används i adressen: all?genre=id. */
data class PlexFilterOption(val id: String, val title: String)

/** Intro- och eftertextsmarkörer som Plex-servern har hittat. */
data class PlexMarker(val type: String, val startMs: Long, val endMs: Long, val final: Boolean = false)

/** Ett kapitel i filen (från filens egen kapitellista, via Plex). */
data class PlexChapter(val title: String, val startMs: Long, val endMs: Long)

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
    /** Filens kapitel; tom lista om filen saknar kapitel (eller bara har ett). */
    val chapters: List<PlexChapter> = emptyList(),
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    val childCount: Int? = null,
    /** Filens id i Plex, behövs för att spara valt ljud- och undertextspår. */
    val partId: Long? = null,
    /** Antal sedda avsnitt i en säsong/serie. */
    val viewedLeafCount: Int? = null,
    /** Titelns logotyp (clearLogo) om Plex har en. */
    val logo: String? = null,
    val ratings: List<PlexRating> = emptyList(),
    val audioChannels: Int? = null,
    /** Plex har gjort förhandsbilder (BIF) för spolning. */
    val hasPreviews: Boolean = false,
    /** IMDb-id, t.ex. "tt0111161". */
    val imdbId: String? = null,
    /** Biblioteket titeln ligger i (för att kunna dölja bibliotek). */
    val sectionKey: String? = null,
    /** Huvudtrailern, t.ex. "/library/metadata/12345". */
    val primaryExtraKey: String? = null,
    /** Extramaterialets typ (1 = trailer) för klipp. */
    val extraType: Int? = null,
    /** Egenskaper för rekommendationer: "g:Action", "d:Christopher Nolan", "a:…", "c:Sverige", "y:1990" … */
    val tags: List<String> = emptyList(),
    /** Tidpunkter i millisekunder. */
    val addedAt: Long = 0L,
    val lastViewedAt: Long = 0L,
    val viewCount: Int = 0,
    /** Seriens ledmotiv, t.ex. "/library/metadata/123/theme/1700000000" (även för säsonger och avsnitt). */
    val theme: String? = null,
    /** Sorteringstitel från Plex (t.ex. "Matrix, The"), för bokstavshoppet i biblioteket. */
    val titleSort: String? = null,
    /** Avsnittets egen bild (16:9), medan thumb är seriens affisch. */
    val still: String? = null,
) {
    /** Märken som 4K, Dolby Vision, Atmos och 5.1, från filens spår (eller grovt från listdata). */
    val badges: List<String>
        get() = buildList {
            val video = streams.firstOrNull { it.type == 1 }
            val res = resolution?.lowercase()
            when {
                res == "4k" || (video?.height ?: 0) >= 2000 -> add("4K")
                res == "1080" || (video?.height ?: 0) >= 1000 -> add("1080p")
                res == "720" -> add("720p")
            }
            when {
                video?.doviPresent == true -> add("Dolby Vision")
                video?.colorTrc == "smpte2084" -> add("HDR10")
                video?.colorTrc == "arib-std-b67" -> add("HLG")
            }
            val audio = streams.firstOrNull { it.type == 2 && it.selected } ?: streams.firstOrNull { it.type == 2 }
            if (audio?.isAtmos == true) add("Atmos")
            when (audio?.channels ?: audioChannels) {
                6 -> add("5.1")
                8 -> add("7.1")
            }
        }

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

    val isCollection: Boolean get() = type == "collection"

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

/** labels: egen text under affischen per ratingKey, t.ex. "3 nya avsnitt". */
data class PlexHomeRow(val title: String, val items: List<PlexItem>, val labels: Map<String, String> = emptyMap())

/** En visning i Plex tittarhistorik. showKey är satt för avsnitt. */
data class PlexHistoryEntry(val ratingKey: String, val type: String, val showKey: String?, val viewedAt: Long)
