package spinescan.core

/**
 * Groups OCR text lines into spine boxes by spatial proximity and orientation.
 *
 * Two text lines belong to the same spine when they are close enough
 * (within [gapFraction] of the smaller dimension) and roughly aligned
 * (their long axes point the same way).
 */
class BoxGrouper(private val thresholds: GrouperThresholds = GrouperThresholds()) {

    data class GrouperThresholds(
        /** Max gap between lines as a fraction of the shorter line's length. */
        val gapFraction: Double = 0.6,
        /** Min overlap along the shared axis as a fraction of the shorter line. */
        val minAxisOverlap: Double = 0.15,
        /** Min area for a box to be kept (pixels). */
        val minBoxArea: Int = 400,
    )

    /**
     * A detected spine box: bounding rect, constituent text lines, combined text.
     */
    data class SpineBox(
        val box: Rect,
        val lines: List<TextLine>,
        val isVertical: Boolean,
    ) {
        /** Combined text from all lines, ordered top-to-bottom then left-to-right. */
        val text: String by lazy {
            lines.sortedWith(compareBy({ it.box.y }, { it.box.x }))
                .joinToString(" ") { it.text }
        }

        /** Average confidence across lines. */
        val confidence: Float by lazy {
            if (lines.isEmpty()) 0f
            else lines.map { it.confidence }.average().toFloat()
        }
    }

    /**
     * Group text lines into spine boxes.
     */
    fun group(lines: List<TextLine>): List<SpineBox> {
        if (lines.isEmpty()) return emptyList()

        // Union-Find to cluster lines
        val parent = IntArray(lines.size) { it }
        val rank = IntArray(lines.size)

        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) parent[ra] = rb
            else if (rank[ra] > rank[rb]) parent[rb] = ra
            else { parent[rb] = ra; rank[ra]++ }
        }

        // Check each pair for proximity and alignment
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (shouldGroup(lines[i], lines[j])) {
                    union(i, j)
                }
            }
        }

        // Collect groups
        val groups = mutableMapOf<Int, MutableList<Int>>()
        for (i in lines.indices) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }

        return groups.values.mapNotNull { indices ->
            val groupLines = indices.map { lines[it] }
            val box = groupLines.map { it.box }.reduce { a, b -> a.union(b) }
            if (box.area < thresholds.minBoxArea) return@mapNotNull null
            val vertical = groupLines.count { it.isVertical } > groupLines.size / 2
            SpineBox(box = box, lines = groupLines, isVertical = vertical)
        }.sortedWith(compareBy({ it.box.y / 50 }, { it.box.x }))  // rough row then left-to-right
    }

    private fun shouldGroup(a: TextLine, b: TextLine): Boolean {
        // Both must have similar orientation
        if (a.isVertical != b.isVertical) return false

        val ra = a.box
        val rb = b.box

        // Lines on the same spine are stacked vertically (top-to-bottom),
        // regardless of text direction. They share a horizontal band (X overlap)
        // and have a small vertical gap.
        val xOverlap = axisOverlap(ra.x, ra.right, rb.x, rb.right)
        val xRef = minOf(ra.width, rb.width)
        if (xRef <= 0 || xOverlap.toDouble() / xRef < thresholds.minAxisOverlap) return false

        val yGap = maxOf(0, maxOf(ra.y - rb.bottom, rb.y - ra.bottom))
        val gapRef = if (a.isVertical) {
            // Vertical text boxes are tall: use width as the scale reference
            xRef
        } else {
            // Horizontal text boxes are wide: use height as the scale reference
            minOf(ra.height, rb.height)
        }
        if (gapRef <= 0) return false
        return yGap <= (gapRef * thresholds.gapFraction).toInt()
    }

    private fun axisOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        maxOf(0, minOf(aEnd, bEnd) - maxOf(aStart, bStart))
}
