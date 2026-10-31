package spinescan.core

/**
 * The four box colours in the scanning overlay.
 *
 * WHITE is unused for now (reserved for unprocessed boxes).
 */
enum class BoxColour {
    /** No text or nothing above the floor. */
    RED,
    /** Likely match or insufficient evidence (disagreeing frames, glare, short text). */
    ORANGE,
    /** High confidence, correct match with agreement. */
    GREEN,
    /** Unprocessed (reserved). */
    WHITE,
}
