package spinescan.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Table-driven tests for colour rules and threshold boundaries.
 */
class BoxStateTest {

    private val defaultThresholds = Thresholds(
        greenScore = 0.85,
        greenLead = 0.10,
        greenFrames = 2,
        greenSingle = 0.95,
        confidentLead = 0.15,
        confidentMinScore = 0.80,
        confidentMinPopularity = 5000,
        greenMinTextLength = 4,
        shortTextMinPopularity = 1000,
        orangeFloor = 0.45,
        apiBatchMs = 300,
        apiRetryMs = 5000,
        apiTimeoutMs = 10000,
    )

    private fun catan(score: Double = 0.95) = TrigramIndex.Candidate(
        bggIds = listOf(13),
        name = "Catan",
        score = score,
    )

    private fun gloomhaven(score: Double = 0.80) = TrigramIndex.Candidate(
        bggIds = listOf(174430),
        name = "Gloomhaven",
        score = score,
    )

    private fun makeBox(
        thresholds: Thresholds = defaultThresholds,
        aliasProvider: AliasProvider = AliasProvider.EMPTY,
    ): BoxState = BoxState(
        boxId = 1,
        thresholds = thresholds,
        aliasProvider = aliasProvider,
        clock = { 0L },
    )

    // --- Red cases ---

    @Test
    fun `no reads is red`() {
        val box = makeBox()
        assertEquals(BoxColour.RED, box.colour)
    }

    @Test
    fun `no text gives red`() {
        val box = makeBox()
        box.addRead("", emptyList())
        assertEquals(BoxColour.RED, box.colour)
    }

    @Test
    fun `score below floor gives red`() {
        val box = makeBox()
        box.addRead("xyz", listOf(catan(score = 0.30)))
        assertEquals(BoxColour.RED, box.colour)
    }

    // --- Orange cases ---

    @Test
    fun `single read above floor below green is orange`() {
        val box = makeBox()
        box.addRead("catan", listOf(catan(score = 0.85)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `high score but only one frame is orange`() {
        val box = makeBox()
        // Score >= greenScore, lead >= greenLead, but only 1 frame (needs 2)
        box.addRead("catan", listOf(catan(score = 0.92)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `disagreeing frames stay orange`() {
        val box = makeBox()
        // Two reads, each high but for different games
        box.addRead("catan", listOf(catan(score = 0.95)))
        box.addRead("gloomhaven", listOf(gloomhaven(score = 0.92)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `glare keeps box orange even with multi-frame agreement`() {
        val box = makeBox()
        box.addRead("catan", listOf(catan(score = 0.95)))
        box.addRead("catan", listOf(catan(score = 0.95)), hasGlare = true)
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `short reads never go green`() {
        val box = makeBox()
        // "pest" is 4 chars, below greenMinTextLength=6 — should never go green
        box.addRead("pest", listOf(catan(score = 0.98)))
        box.addRead("pest", listOf(catan(score = 0.98)))
        box.addRead("pest", listOf(catan(score = 0.98)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    // --- Green cases ---

    @Test
    fun `multi-frame agreement goes green`() {
        val box = makeBox()
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("settlers catan", listOf(catan(score = 0.93)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    @Test
    fun `very high single read goes green`() {
        val box = makeBox()
        // Score >= greenSingle (0.95), lead >= greenLead
        box.addRead("settlers catan", listOf(catan(score = 0.96)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    @Test
    fun `one wrong read among correct ones does not change green`() {
        val box = makeBox()
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("settlers catan", listOf(catan(score = 0.93)))
        assertEquals(BoxColour.GREEN, box.colour)
        // Now a wrong read — still green because best candidate has more votes
        box.addRead("gloomhaven", listOf(gloomhaven(score = 0.70)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    // --- Threshold boundary tests ---

    @Test
    fun `score exactly at green threshold with enough frames goes green`() {
        val box = makeBox()
        box.addRead("settlers catan", listOf(catan(score = 0.85)))
        box.addRead("settlers catan", listOf(catan(score = 0.85)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    @Test
    fun `score just below green threshold stays orange`() {
        val box = makeBox()
        box.addRead("settlers catan", listOf(catan(score = 0.849)))
        box.addRead("settlers catan", listOf(catan(score = 0.849)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `score at orange floor is orange`() {
        val box = makeBox()
        box.addRead("settlers catan", listOf(catan(score = 0.45)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `score just below orange floor is red`() {
        val box = makeBox()
        box.addRead("settlers catan", listOf(catan(score = 0.449)))
        assertEquals(BoxColour.RED, box.colour)
    }

    @Test
    fun `lead above threshold goes green`() {
        val box = makeBox()
        // Two agreeing reads for catan at 0.95, one disagreeing for gloomhaven at 0.80
        // lead = 0.95 - 0.80 = 0.15 (above threshold 0.10)
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("gloomhaven", listOf(gloomhaven(score = 0.80)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    @Test
    fun `lead below threshold stays orange`() {
        val box = makeBox()
        // Two agreeing reads for catan at 0.95, one disagreeing for gloomhaven at 0.90
        // lead = 0.95 - 0.90 = 0.05 (below threshold 0.10)
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("gloomhaven", listOf(gloomhaven(score = 0.90)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    // --- Changing thresholds changes behaviour ---

    @Test
    fun `lowering green threshold makes orange go green`() {
        val relaxed = defaultThresholds.copy(greenScore = 0.80)
        val box = makeBox(thresholds = relaxed)
        box.addRead("settlers catan", listOf(catan(score = 0.85)))
        box.addRead("settlers catan", listOf(catan(score = 0.85)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    @Test
    fun `raising green threshold makes green go orange`() {
        val strict = defaultThresholds.copy(greenScore = 0.99, greenSingle = 0.99)
        val box = makeBox(thresholds = strict)
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        box.addRead("settlers catan", listOf(catan(score = 0.95)))
        assertEquals(BoxColour.ORANGE, box.colour)
    }

    @Test
    fun `increasing frame requirement makes two-frame green go orange`() {
        val strict = defaultThresholds.copy(greenFrames = 3, greenSingle = 0.99)
        val box = makeBox(thresholds = strict)
        box.addRead("settlers catan", listOf(catan(score = 0.94)))
        box.addRead("settlers catan", listOf(catan(score = 0.93)))
        assertEquals(BoxColour.ORANGE, box.colour)
        // Third frame makes it green
        box.addRead("settlers catan", listOf(catan(score = 0.91)))
        assertEquals(BoxColour.GREEN, box.colour)
    }

    // --- Alias provider ---

    @Test
    fun `alias takes priority over search`() {
        val alias = AliasProvider { text ->
            if (text == "settlers of catan") catan(score = 1.0) else null
        }
        val box = makeBox(aliasProvider = alias)
        box.addRead("settlers of catan", emptyList())
        box.addRead("settlers of catan", emptyList())
        assertEquals(BoxColour.GREEN, box.colour)
        assertEquals("Catan", box.bestCandidate?.name)
    }
}
