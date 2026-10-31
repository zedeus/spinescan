package spinescan.core

/**
 * In-memory trigram index for fuzzy name lookup.
 *
 * Uses Jaccard similarity on character trigrams with popularity tiebreak.
 * Games sharing a normalised title text count as one candidate group.
 */
class TrigramIndex {

    /** A candidate returned by lookup. */
    data class Candidate(
        /** All BGG IDs sharing this normalised name. */
        val bggIds: List<Int>,
        val name: String,
        val score: Double,
    )

    private class Entry(
        val bggId: Int,
        val rawName: String,
        val normStripped: String,
        val popularity: Int,
        val trigramSet: Set<String>,
        val trigramCount: Int = trigramSet.size,
    )

    /** Group of entries sharing a normalised (stripped) name. */
    private class NameGroup(
        val bggIds: MutableList<Int> = mutableListOf(),
        var bestName: String = "",
        val normStripped: String,
        var bestPopularity: Int = 0,
    )

    private val entries = mutableListOf<Entry>()
    // trigram → list of entry indices
    private val index = mutableMapOf<String, MutableList<Int>>()
    // normStripped → group for games sharing a title
    private val groups = mutableMapOf<String, NameGroup>()

    fun add(bggId: Int, rawName: String, normName: String, normStripped: String, popularity: Int) {
        val idx = entries.size
        val tris = trigramSet(normName)
        val entry = Entry(bggId, rawName, normStripped, popularity, tris)
        entries.add(entry)

        for (tri in tris) {
            index.getOrPut(tri) { mutableListOf() }.add(idx)
        }

        // Track name groups for games sharing a title
        val group = groups.getOrPut(normStripped) { NameGroup(normStripped = normStripped) }
        if (bggId !in group.bggIds) {
            group.bggIds.add(bggId)
        }
        if (popularity > group.bestPopularity) {
            group.bestPopularity = popularity
            group.bestName = rawName
        }
    }

    /**
     * Lookup candidates for a query string.
     *
     * @param query normalised query text
     * @param topN maximum results to return
     * @return candidates sorted by score desc, popularity as tiebreak
     */
    fun lookup(query: String, topN: Int = 5): List<Candidate> {
        val queryTris = trigramSet(query)
        if (queryTris.isEmpty()) return emptyList()
        val querySize = queryTris.size

        // Count shared trigrams per entry
        val counts = mutableMapOf<Int, Int>()
        for (tri in queryTris) {
            for (idx in index[tri] ?: continue) {
                counts[idx] = (counts[idx] ?: 0) + 1
            }
        }

        // Score top candidates, deduplicate by normStripped
        val seen = mutableMapOf<String, ScoredGroup>()
        val limit = topN * 5
        val topEntries = counts.entries
            .sortedByDescending { it.value }
            .take(limit)

        for ((idx, shared) in topEntries) {
            val entry = entries[idx]
            // Union size = |A| + |B| - |A ∩ B|, no allocation needed
            val union = querySize + entry.trigramCount - shared
            val score = if (union > 0) shared.toDouble() / union else 0.0

            val existing = seen[entry.normStripped]
            if (existing == null || score > existing.score ||
                (score == existing.score && entry.popularity > existing.popularity)
            ) {
                val group = groups[entry.normStripped]
                seen[entry.normStripped] = ScoredGroup(
                    score = score,
                    bestName = group?.bestName ?: entry.rawName,
                    popularity = group?.bestPopularity ?: entry.popularity,
                )
            }
        }

        return seen.entries
            .sortedWith(compareByDescending<Map.Entry<String, ScoredGroup>> { it.value.score }
                .thenByDescending { it.value.popularity })
            .take(topN)
            .map { (normStripped, scored) ->
                val group = groups[normStripped]
                Candidate(
                    bggIds = group?.bggIds?.toList() ?: emptyList(),
                    name = scored.bestName,
                    score = scored.score,
                )
            }
    }

    val size: Int get() = entries.size

    private class ScoredGroup(
        val score: Double,
        val bestName: String,
        val popularity: Int,
    )

    companion object {
        /** Generate character trigrams with space padding. */
        fun trigrams(s: String): List<String> {
            val padded = "  $s "
            return (0 until padded.length - 2).map { i ->
                padded.substring(i, i + 3)
            }
        }

        /** Generate character trigrams as a Set (avoids intermediate List). */
        private fun trigramSet(s: String): Set<String> {
            val padded = "  $s "
            val set = HashSet<String>(padded.length)
            for (i in 0 until padded.length - 2) {
                set.add(padded.substring(i, i + 3))
            }
            return set
        }
    }
}
