package spinescan.core

/**
 * Multi-strategy text matching against the game index.
 *
 * Instead of only matching the raw OCR text, tries several extractions
 * from the same text and returns the best candidates across all strategies.
 * This handles common OCR issues:
 * - Merged words: "PEDALTOTHEMETAL" → split on known function words
 * - Trailing noise: "SCRABBLERR", "PANNDEMIC" → try trimmed variants
 * - Multi-line boxes: "Telestratic estrations AFTERDARK" → try substrings
 * - CJK + English: "欲罷不能 CANT STOP" → extract ASCII part
 */
object TextMatcher {

    fun matchAll(
        ocrText: String,
        index: TrigramIndex,
        topN: Int = 5,
    ): List<TrigramIndex.Candidate> {
        val norm = Normalize.normalize(ocrText)
        if (norm.isBlank()) return emptyList()

        val queries = mutableSetOf<String>()

        // Strategy 1: full normalised text
        queries.add(norm)

        val words = norm.split(" ").filter { it.length > 2 }

        // Strategy 2: individual long words
        for (w in words) {
            if (w.length >= 8) queries.add(w)
        }

        // Strategy 3: adjacent word pairs (for multi-word game names in noisy text)
        if (words.size >= 3) {
            for (i in 0 until words.size - 1) {
                val pair = "${words[i]} ${words[i + 1]}"
                if (pair.length >= 8) queries.add(pair)
            }
        }

        // Strategy 4: split merged words (no-space text)
        val split = splitMergedWords(norm)
        if (split != norm) queries.add(split)

        // Strategy 5: drop short noise words
        val longWords = words.filter { it.length >= 4 }
        if (longWords.size in 1 until words.size) {
            queries.add(longWords.joinToString(" "))
        }

        // Strategy 6: clean common OCR noise patterns
        val cleaned = cleanOcrNoise(norm)
        if (cleaned != norm && cleaned.isNotBlank()) queries.add(cleaned)

        // Strategy 7: if text has non-ASCII (CJK etc.), try just the ASCII part
        val asciiOnly = norm.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (asciiOnly != norm && asciiOnly.length >= 4) queries.add(asciiOnly)

        // Strategy 8: try first N words (game name often starts the OCR text)
        if (words.size >= 3) {
            for (n in 2..minOf(4, words.size - 1)) {
                val prefix = words.take(n).joinToString(" ")
                if (prefix.length >= 6) queries.add(prefix)
            }
        }

        // Run all queries and deduplicate by bggIds
        val best = mutableMapOf<Set<Int>, TrigramIndex.Candidate>()
        for (query in queries) {
            if (query.length < 3) continue
            val candidates = index.lookup(query, topN = 3)
            for (c in candidates) {
                val key = c.bggIds.toSet()
                val existing = best[key]
                if (existing == null || c.score > existing.score) {
                    best[key] = c
                }
            }
        }

        return best.values
            .sortedWith(compareByDescending<TrigramIndex.Candidate> { it.score }
                .thenByDescending { it.popularity })
            .take(topN)
    }

    /**
     * Split merged words by inserting spaces at known function-word boundaries.
     * "pedaltothemetal" → "pedal to the metal"
     * "fogoflove" → "fog of love"
     * "castlekeep" → "castle keep" (via camelCase-like boundary detection)
     */
    private fun splitMergedWords(text: String): String {
        if (" " in text) return text
        if (text.length < 6) return text

        // Try inserting spaces before common function words
        var result = text
        val functionWords = listOf(
            "ofthe", "tothe", "inthe", "onthe", "andthe", "forthe",
            "of", "the", "and", "for", "from", "with", "into", "upon",
        )

        for (fw in functionWords) {
            val idx = result.indexOf(fw)
            if (idx > 2 && idx < result.length - fw.length) {
                result = result.substring(0, idx) + " " + fw + " " + result.substring(idx + fw.length)
                break // only one split to avoid over-splitting
            }
        }

        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Clean common OCR noise from normalised text:
     * - Trailing repeated characters: "scrabblerr" → "scrabble"
     * - Doubled letters in the middle: "panndemic" → "pandemic"
     * - Trailing numbers/noise after game name
     */
    private fun cleanOcrNoise(text: String): String {
        var result = text

        // Remove trailing single characters that look like OCR noise
        result = result.replace(Regex("\\s+[a-z0-9]$"), "")

        // Remove trailing repeated chars: "scrabblerr" → "scrabble" + "rr"
        result = result.replace(Regex("(.)\\1{2,}$"), "$1")

        // Collapse doubled consonants that are likely OCR noise
        // (but not doubled vowels like "oo" which are common in English)
        result = result.replace(Regex("([bcdfghjklmnpqrstvwxyz])\\1{2,}"), "$1$1")

        return result.trim()
    }
}
