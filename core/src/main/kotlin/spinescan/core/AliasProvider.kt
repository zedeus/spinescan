package spinescan.core

/**
 * Interface for confirmed aliases — text that maps directly to a known game
 * without going through the trigram index.
 *
 * Aliases take priority over search results. The Room implementation
 * comes in issue #9.
 */
fun interface AliasProvider {
    /** Resolve text to a confirmed candidate, or null if not an alias. */
    fun resolve(text: String): TrigramIndex.Candidate?

    companion object {
        /** Empty provider that never resolves anything. */
        val EMPTY: AliasProvider = AliasProvider { null }
    }
}
