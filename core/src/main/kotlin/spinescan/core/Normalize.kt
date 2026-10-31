package spinescan.core

import java.text.Normalizer

/**
 * Name normalisation — Kotlin port of Python normalize.py.
 * Single definition, tested against shared fixture file.
 *
 * Steps:
 * 1. Unicode NFKD decomposition, strip combining marks (accents)
 * 2. Lowercase
 * 3. Remove all punctuation (keep letters, digits, whitespace)
 * 4. Collapse whitespace
 * 5. Strip leading/trailing whitespace
 */
object Normalize {

    /** Edition words set aside during matching, not dropped from display. Sorted for binary search. */
    val EDITION_WORDS: List<String> = listOf(
        "anniversary", "classic", "collector", "collectors", "deluxe",
        "edition", "essential", "giant", "limited", "master", "mega",
        "mini", "pocket", "premium", "revised", "second", "third",
        "travel", "ultimate",
    )

    private val PUNCT_RE = Regex("[^\\w\\s]")
    private val MULTI_SPACE_RE = Regex("\\s+")

    fun normalize(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD)
        val withoutAccents = buildString {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
        val lower = withoutAccents.lowercase()
        val noPunct = PUNCT_RE.replace(lower, " ")
        return MULTI_SPACE_RE.replace(noPunct, " ").trim()
    }

    /**
     * Remove edition words from a normalised name.
     * Returns (name_without_edition_words, list_of_found_edition_words).
     */
    fun stripEditionWords(normalized: String): Pair<String, List<String>> {
        val words = normalized.split(" ")
        val found = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (w in words) {
            if (EDITION_WORDS.binarySearch(w) >= 0) {
                found.add(w)
            } else {
                kept.add(w)
            }
        }
        return kept.joinToString(" ") to found
    }
}
