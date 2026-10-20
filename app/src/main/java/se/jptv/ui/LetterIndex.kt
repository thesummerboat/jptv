package se.jptv.ui

import java.text.Normalizer

/** Bokstavshopp i biblioteket: vilken bokstav varje titel hör till och var bokstäverna börjar. */
object LetterIndex {
    /** "Matrix" → "M", "Älskade" → "Ä", "Éclair" → "E", "2001" → "#". */
    fun letterOf(title: String): String {
        val c = title.trimStart().firstOrNull { it.isLetterOrDigit() } ?: return "#"
        if (!c.isLetter()) return "#"
        val up = c.uppercaseChar()
        if (up == 'Å' || up == 'Ä' || up == 'Ö') return up.toString()
        // Accenter bort (É → E); svenska å, ä och ö behålls ovan.
        val base = Normalizer.normalize(up.toString(), Normalizer.Form.NFD).first()
        return if (base in 'A'..'Z') base.toString() else "#"
    }

    /**
     * (bokstav, plats för första titeln) i listans ordning. Ordningen följer Plex sortering, så att
     * bokstäverna hamnar där Plex lägger dem (t.ex. "The Matrix" under M via sorteringstiteln).
     */
    fun build(titles: List<String>): List<Pair<String, Int>> {
        val first = LinkedHashMap<String, Int>()
        titles.forEachIndexed { i, t -> first.getOrPut(letterOf(t)) { i } }
        return first.toList()
    }
}
