package spinescan.core.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import spinescan.core.BoxColour
import spinescan.core.OcrEngine
import spinescan.core.PackLoader
import spinescan.core.Pipeline
import spinescan.core.Rect
import spinescan.core.TextLine
import spinescan.core.Thresholds
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Swapping in a freshly downloaded pack must not change colours already locked. */
class PackSwapTest {

    @TempDir
    lateinit var dir: File

    private val thresholds = Thresholds(
        greenScore = 0.90, greenLead = 0.10, greenFrames = 2,
        greenSingle = 0.97, orangeFloor = 0.60,
        apiBatchMs = 300, apiRetryMs = 5000, apiTimeoutMs = 10000,
    )

    private fun pack(games: List<TestPacks.Game>): PackLoader.Pack {
        val f = File(dir, "pack-${games.hashCode()}.db")
        TestPacks.write(f, games)
        return PackLoader.load(f.path)
    }

    private val catanOcr = OcrEngine { _, _, _ ->
        listOf(TextLine(Rect(10, 10, 40, 200), "Catan", 0.99f, isVertical = true))
    }

    @Test
    fun `pack swap mid-sweep keeps locked colours`() {
        val withCatan = pack(TestPacks.DEFAULT_GAMES)
        val withoutCatan = pack(TestPacks.DEFAULT_GAMES.filter { it.name != "Catan" })

        val pipeline = Pipeline(catanOcr, withCatan, thresholds)
        val before = pipeline.processFrame(ByteArray(0), 100, 300, frameIndex = 0)
        val lockedBoxId = before.boxes[0].boxId
        assertEquals(BoxColour.GREEN, before.boxes[0].colour, "exact match locks green")

        pipeline.pack = withoutCatan

        // The already-locked box keeps its colour…
        assertEquals(BoxColour.GREEN, pipeline.states[lockedBoxId]!!.colour)

        // …while new reads use the new index and no longer find Catan.
        val after = pipeline.processFrame(ByteArray(0), 100, 300, frameIndex = 1)
        assertNotEquals(BoxColour.GREEN, after.boxes[0].colour)
        assertEquals(BoxColour.GREEN, pipeline.states[lockedBoxId]!!.colour)
    }
}
