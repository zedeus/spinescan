package spinescan.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Box grouping tests on hand-made layouts.
 *
 * Done-when: "two lines on one spine, adjacent spines, tilted spines,
 * a stray line; a blank spine gives a red box"
 */
class BoxGrouperTest {

    private val grouper = BoxGrouper()

    /** Two horizontal text lines on the same spine should group into one box. */
    @Test
    fun `two lines on one spine group together`() {
        val lines = listOf(
            TextLine(Rect(100, 50, 200, 30), "TICKET", 0.9f),
            TextLine(Rect(100, 90, 200, 30), "TO RIDE", 0.9f),
        )
        val boxes = grouper.group(lines)
        assertEquals(1, boxes.size, "Two close horizontal lines should form one box")
        assertTrue(boxes[0].text.contains("TICKET"), "Box text should contain TICKET")
        assertTrue(boxes[0].text.contains("TO RIDE"), "Box text should contain TO RIDE")
    }

    /** Three adjacent horizontal spines should form separate boxes. */
    @Test
    fun `adjacent spines form separate boxes`() {
        val lines = listOf(
            TextLine(Rect(30, 60, 120, 30), "CATAN", 0.9f),
            TextLine(Rect(350, 60, 100, 30), "AZUL", 0.9f),
            TextLine(Rect(650, 60, 100, 30), "ROOT", 0.9f),
        )
        val boxes = grouper.group(lines)
        assertEquals(3, boxes.size, "Three spaced-out lines should form three boxes")
    }

    /** Two vertical text lines on the same spine should group. */
    @Test
    fun `vertical lines on one spine group together`() {
        val lines = listOf(
            // Two vertical lines close together: gap = 135 - 130 = 5, xRef = 30, threshold = 18
            TextLine(Rect(50, 10, 30, 120), "WING", 0.9f, isVertical = true),
            TextLine(Rect(50, 135, 30, 100), "SPAN", 0.9f, isVertical = true),
        )
        val boxes = grouper.group(lines)
        assertEquals(1, boxes.size, "Two close vertical lines should form one box")
        assertTrue(boxes[0].isVertical)
    }

    /** A horizontal line and a vertical line should not group. */
    @Test
    fun `different orientations stay separate`() {
        val lines = listOf(
            TextLine(Rect(50, 50, 150, 30), "CATAN", 0.9f, isVertical = false),
            TextLine(Rect(80, 50, 30, 150), "ROOT", 0.9f, isVertical = true),
        )
        val boxes = grouper.group(lines)
        assertEquals(2, boxes.size, "Different orientations should not group")
    }

    /** A small stray line far from the main text stays separate. */
    @Test
    fun `stray line stays separate`() {
        val lines = listOf(
            TextLine(Rect(60, 60, 120, 15), "2-4 PLAYERS", 0.7f),
            TextLine(Rect(60, 250, 200, 40), "SCYTHE", 0.95f),
        )
        val boxes = grouper.group(lines)
        // They should not group because the gap is much larger than the reference size
        assertEquals(2, boxes.size, "Distant lines should form separate boxes")
    }

    /** Empty input gives empty output. */
    @Test
    fun `empty input gives empty output`() {
        assertEquals(emptyList(), grouper.group(emptyList()))
    }

    /** Very small boxes below the area threshold are dropped. */
    @Test
    fun `tiny boxes are filtered out`() {
        val lines = listOf(
            TextLine(Rect(10, 10, 5, 5), ".", 0.5f),
        )
        val boxes = grouper.group(lines)
        assertTrue(boxes.isEmpty(), "Tiny boxes should be filtered")
    }

    /** Boxes are sorted roughly by row then left-to-right. */
    @Test
    fun `boxes sorted by position`() {
        val lines = listOf(
            TextLine(Rect(500, 50, 150, 30), "THIRD", 0.9f),
            TextLine(Rect(50, 50, 150, 30), "FIRST", 0.9f),
            TextLine(Rect(250, 50, 150, 30), "SECOND", 0.9f),
        )
        val boxes = grouper.group(lines)
        assertEquals(3, boxes.size)
        assertEquals("FIRST", boxes[0].text)
        assertEquals("SECOND", boxes[1].text)
        assertEquals("THIRD", boxes[2].text)
    }

    /** Two games stacked with a large vertical gap should form two boxes. */
    @Test
    fun `vertical gap separates boxes`() {
        val lines = listOf(
            // First game: two lines close together
            TextLine(Rect(30, 60, 200, 35), "TICKET", 0.9f),
            TextLine(Rect(30, 100, 200, 35), "TO RIDE", 0.9f),
            // Second game: far below
            TextLine(Rect(30, 380, 200, 35), "PANDEMIC", 0.9f),
        )
        val boxes = grouper.group(lines)
        assertEquals(2, boxes.size, "Games with a large gap should form separate boxes")
        assertTrue(boxes[0].text.contains("TICKET"))
        assertTrue(boxes[1].text.contains("PANDEMIC"))
    }

    /** Combined text is ordered top-to-bottom for horizontal lines. */
    @Test
    fun `combined text ordered correctly`() {
        val lines = listOf(
            // Lines are close together (gap = 95 - 90 = 5, within threshold)
            TextLine(Rect(30, 95, 200, 30), "TO RIDE", 0.9f),
            TextLine(Rect(30, 60, 200, 30), "TICKET", 0.9f),
        )
        val boxes = grouper.group(lines)
        assertEquals(1, boxes.size)
        // Should be "TICKET TO RIDE" regardless of input order
        assertEquals("TICKET TO RIDE", boxes[0].text)
    }
}
