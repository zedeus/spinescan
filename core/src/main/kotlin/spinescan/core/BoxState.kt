package spinescan.core

/**
 * Per-box reading history and colour decision.
 *
 * A box is identified by an external ID (assigned by the tracker in #6).
 * Each OCR read is recorded with its lookup result, and the colour is
 * recomputed from the full history.
 */
class BoxState(
    val boxId: Int,
    private val thresholds: Thresholds,
    private val aliasProvider: AliasProvider = AliasProvider.EMPTY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A single OCR read and its lookup result. */
    data class Read(
        val text: String,
        val candidate: TrigramIndex.Candidate?,
        val score: Double,
        val timestamp: Long,
        val hasGlare: Boolean = false,
    )

    private val reads = mutableListOf<Read>()

    /** The current colour decision. */
    var colour: BoxColour = BoxColour.RED
        private set

    /** The best candidate across all reads, if any. */
    var bestCandidate: TrigramIndex.Candidate? = null
        private set

    /** Record a new read and recompute the colour. */
    fun addRead(
        text: String,
        candidates: List<TrigramIndex.Candidate>,
        hasGlare: Boolean = false,
    ) {
        // Check aliases first
        val aliasMatch = aliasProvider.resolve(text)

        val candidate: TrigramIndex.Candidate?
        val score: Double

        if (aliasMatch != null) {
            candidate = aliasMatch
            score = 1.0
        } else if (candidates.isNotEmpty()) {
            candidate = candidates[0]
            score = candidate.score
        } else {
            candidate = null
            score = 0.0
        }

        reads.add(Read(text, candidate, score, clock(), hasGlare))
        recompute()
    }

    /** Recompute colour from the full history. */
    private fun recompute() {
        if (reads.isEmpty()) {
            colour = BoxColour.RED
            bestCandidate = null
            return
        }

        // Short reads (3 chars or fewer) never go green
        val allShort = reads.all { it.text.length <= 3 }

        // Group reads by candidate (by bggIds set)
        val candidateVotes = mutableMapOf<Set<Int>, CandidateVote>()
        for (read in reads) {
            val c = read.candidate ?: continue
            val key = c.bggIds.toSet()
            val vote = candidateVotes.getOrPut(key) {
                CandidateVote(c, 0, 0.0)
            }
            vote.count++
            vote.bestScore = maxOf(vote.bestScore, read.score)
        }

        if (candidateVotes.isEmpty()) {
            colour = BoxColour.RED
            bestCandidate = null
            return
        }

        // Best candidate by vote count, then best score
        val sorted = candidateVotes.entries
            .sortedWith(compareByDescending<Map.Entry<Set<Int>, CandidateVote>> { it.value.count }
                .thenByDescending { it.value.bestScore })
        val bestKey = sorted[0].key
        val best = sorted[0].value
        val runnerUp = sorted.getOrNull(1)?.value

        bestCandidate = best.candidate

        // Any read has glare? Can't go green.
        val anyGlare = reads.any { it.hasGlare && it.candidate?.bggIds?.toSet() == bestKey }

        // Lead over runner-up
        val lead = if (runnerUp != null) {
            best.bestScore - runnerUp.bestScore
        } else {
            best.bestScore // No runner-up: lead is the score itself
        }

        // Colour rules — all thresholds from config.
        // Evaluated top-to-bottom; earlier branches guard later ones.
        colour = when {
            // No text or nothing above the floor
            best.bestScore < thresholds.orangeFloor -> BoxColour.RED

            // Glare prevents green
            anyGlare -> BoxColour.ORANGE

            // Short reads never go green
            allShort -> BoxColour.ORANGE

            // Disagreeing frames: different candidates with enough votes
            runnerUp != null && runnerUp.count >= thresholds.greenFrames -> BoxColour.ORANGE

            // Very high single read (no multi-frame needed)
            best.bestScore >= thresholds.greenSingle &&
                lead >= thresholds.greenLead -> BoxColour.GREEN

            // Multi-frame agreement
            best.bestScore >= thresholds.greenScore &&
                lead >= thresholds.greenLead &&
                best.count >= thresholds.greenFrames -> BoxColour.GREEN

            // Likely match but not enough evidence
            else -> BoxColour.ORANGE
        }
    }

    val readCount: Int get() = reads.size

    private class CandidateVote(
        val candidate: TrigramIndex.Candidate,
        var count: Int = 0,
        var bestScore: Double = 0.0,
    )
}
