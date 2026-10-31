package spinescan.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ThresholdsTest {

    @Test
    fun `loads thresholds from resource`() {
        val t = Thresholds.load()
        assertEquals(0.90, t.greenScore)
        assertEquals(0.10, t.greenLead)
        assertEquals(2, t.greenFrames)
        assertEquals(0.97, t.greenSingle)
        assertEquals(0.60, t.orangeFloor)
        assertEquals(300L, t.apiBatchMs)
        assertEquals(5000L, t.apiRetryMs)
        assertEquals(10000L, t.apiTimeoutMs)
    }

    @Test
    fun `missing key throws`() {
        val input = "green.score=0.9\n".byteInputStream()
        assertThrows<IllegalStateException> {
            Thresholds.load(input)
        }
    }
}
