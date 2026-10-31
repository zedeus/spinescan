package spinescan.core

/**
 * Axis-aligned bounding rectangle for image regions.
 * Coordinates are in pixels, origin at top-left.
 */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    val area: Int get() = width * height

    /** Check if this rect overlaps another. */
    fun overlaps(other: Rect): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y

    /** Expand to include another rect. */
    fun union(other: Rect): Rect {
        val nx = minOf(x, other.x)
        val ny = minOf(y, other.y)
        return Rect(nx, ny, maxOf(right, other.right) - nx, maxOf(bottom, other.bottom) - ny)
    }

    /** Expand by a margin on all sides. */
    fun expand(margin: Int): Rect =
        Rect(x - margin, y - margin, width + 2 * margin, height + 2 * margin)

    /** Intersection area with another rect. */
    fun intersectionArea(other: Rect): Int {
        val ox = maxOf(x, other.x)
        val oy = maxOf(y, other.y)
        val ow = minOf(right, other.right) - ox
        val oh = minOf(bottom, other.bottom) - oy
        return if (ow > 0 && oh > 0) ow * oh else 0
    }
}
