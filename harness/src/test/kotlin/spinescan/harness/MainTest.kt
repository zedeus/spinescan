package spinescan.harness

import org.junit.jupiter.api.Test
import spinescan.core.Thresholds
import kotlin.test.assertTrue

class MainTest {

    @Test
    fun `thresholds load from core`() {
        val t = Thresholds.load()
        assertTrue(t.greenScore > 0)
    }
}
