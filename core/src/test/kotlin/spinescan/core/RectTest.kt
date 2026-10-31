package spinescan.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RectTest {

    @Test
    fun `overlapping rects`() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(50, 50, 100, 100)
        assertTrue(a.overlaps(b))
        assertTrue(b.overlaps(a))
    }

    @Test
    fun `non-overlapping rects`() {
        val a = Rect(0, 0, 50, 50)
        val b = Rect(100, 100, 50, 50)
        assertFalse(a.overlaps(b))
    }

    @Test
    fun `union of rects`() {
        val a = Rect(10, 20, 30, 40)
        val b = Rect(50, 10, 30, 50)
        val u = a.union(b)
        assertEquals(Rect(10, 10, 70, 50), u)
    }

    @Test
    fun `intersection area`() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(50, 50, 100, 100)
        assertEquals(2500, a.intersectionArea(b))
    }

    @Test
    fun `no intersection area`() {
        val a = Rect(0, 0, 50, 50)
        val b = Rect(100, 100, 50, 50)
        assertEquals(0, a.intersectionArea(b))
    }

    @Test
    fun `expand adds margin`() {
        val r = Rect(10, 20, 30, 40)
        val e = r.expand(5)
        assertEquals(Rect(5, 15, 40, 50), e)
    }
}
