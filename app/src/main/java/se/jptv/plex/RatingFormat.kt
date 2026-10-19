package se.jptv.plex

import java.util.Locale

/** Gör om Plex betyg (bild-id + värde 0–10) till korta märken: "IMDb 7,6", "RT 91 %". */
object RatingFormat {
    fun format(raw: List<Pair<String, Double>>): List<PlexRating> {
        val out = LinkedHashMap<String, PlexRating>()
        for ((image, value) in raw) {
            if (value <= 0.0) continue
            val img = image.lowercase()
            val rating = when {
                img.startsWith("imdb://") -> PlexRating("IMDb", "IMDb ${decimal(value)}", value)
                img.startsWith("rottentomatoes://") && (img.endsWith(".ripe") || img.endsWith(".rotten")) ->
                    PlexRating("RT", "RT ${percent(value)}", value)
                img.startsWith("rottentomatoes://") && (img.endsWith(".upright") || img.endsWith(".spilled")) ->
                    PlexRating("RT publik", "RT publik ${percent(value)}", value)
                img.startsWith("themoviedb://") -> PlexRating("TMDB", "TMDB ${decimal(value)}", value)
                else -> null
            } ?: continue
            out.putIfAbsent(rating.source, rating)
        }
        return out.values.toList()
    }

    private fun decimal(v: Double) = String.format(Locale("sv", "SE"), "%.1f", v)

    private fun percent(v: Double) = "${Math.round(v * 10)} %"
}
