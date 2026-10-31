package spinescan.core

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrigramIndexTest {

    private lateinit var index: TrigramIndex

    @BeforeEach
    fun setup() {
        index = TrigramIndex()
        // A small pack with known games
        val games = listOf(
            Triple(13, "Catan", 100000),
            Triple(325, "Catan: Seafarers", 40000),
            Triple(926, "Catan: Cities & Knights", 30000),
            Triple(174430, "Gloomhaven", 60000),
            Triple(167791, "Terraforming Mars", 80000),
            Triple(169786, "Scythe", 70000),
            Triple(233078, "Azul", 50000),
            Triple(68448, "7 Wonders", 90000),
            Triple(9209, "Ticket to Ride", 95000),
            Triple(14996, "Ticket to Ride: Europe", 85000),
        )
        for ((bggId, name, pop) in games) {
            val norm = Normalize.normalize(name)
            val (stripped, _) = Normalize.stripEditionWords(norm)
            index.add(bggId, name, norm, stripped, pop)
        }
    }

    @Test
    fun `exact match`() {
        val results = index.lookup("catan")
        assertTrue(results.isNotEmpty())
        assertTrue(13 in results[0].bggIds)
    }

    @Test
    fun `typo match`() {
        // "catn" — missing 'a'
        val results = index.lookup("catn")
        assertTrue(results.isNotEmpty())
        assertTrue(13 in results[0].bggIds)
    }

    @Test
    fun `partial match`() {
        // "terraform" — prefix of "terraforming mars"
        val results = index.lookup("terraform")
        assertTrue(results.isNotEmpty())
        assertTrue(167791 in results[0].bggIds)
    }

    @Test
    fun `subtitle only match`() {
        // "seafarers" — subtitle of "Catan: Seafarers"
        val results = index.lookup("seafarers")
        assertTrue(results.isNotEmpty())
        assertTrue(325 in results[0].bggIds)
    }

    @Test
    fun `sequel vs base game`() {
        // "ticket to ride" should match the base game (higher popularity)
        val results = index.lookup("ticket to ride")
        assertTrue(results.isNotEmpty())
        assertTrue(9209 in results[0].bggIds)
    }

    @Test
    fun `ticket to ride europe matches its own entry`() {
        val results = index.lookup("ticket to ride europe")
        assertTrue(results.isNotEmpty())
        assertTrue(14996 in results[0].bggIds)
    }

    @Test
    fun `alternate language match`() {
        // Add a German name for Catan
        val norm = Normalize.normalize("Die Siedler von Catan")
        val (stripped, _) = Normalize.stripEditionWords(norm)
        index.add(13, "Die Siedler von Catan", norm, stripped, 100000)

        val results = index.lookup("die siedler von catan")
        assertTrue(results.isNotEmpty())
        assertTrue(13 in results[0].bggIds)
    }

    @Test
    fun `games sharing title counted as one candidate`() {
        // Add two games with identical normalised name (reprints)
        val idx = TrigramIndex()
        idx.add(1001, "Clue", "clue", "clue", 50000)
        idx.add(1002, "Clue", "clue", "clue", 30000)

        val results = idx.lookup("clue")
        assertTrue(results.isNotEmpty())
        // Should be one candidate with both IDs
        assertTrue(results[0].bggIds.size == 2)
        assertTrue(1001 in results[0].bggIds)
        assertTrue(1002 in results[0].bggIds)
    }

    @Test
    fun `empty query returns empty`() {
        assertEquals(emptyList(), index.lookup(""))
    }

    @Test
    fun `trigrams generates correct output`() {
        val tris = TrigramIndex.trigrams("ab")
        // "  ab " → "  a", " ab", "ab "
        assertEquals(listOf("  a", " ab", "ab "), tris)
    }

    @Test
    fun `popularity tiebreak`() {
        // "7 wonders" vs "azul" — both short, but querying "7 wonders" should rank it first
        val results = index.lookup("7 wonders")
        assertTrue(results.isNotEmpty())
        assertTrue(68448 in results[0].bggIds)
    }
}
