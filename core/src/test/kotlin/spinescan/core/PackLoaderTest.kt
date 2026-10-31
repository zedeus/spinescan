package spinescan.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import spinescan.core.api.TestPacks
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackLoaderTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `loads a v2 names-only pack`() {
        val f = File(dir, "pack.db")
        TestPacks.write(f)
        val pack = PackLoader.load(f.path)
        assertEquals(2, pack.version)
        assertEquals(3, pack.games.size)
        assertEquals("Catan", pack.games[13]!!.name)
        assertEquals(1995, pack.games[13]!!.year)
        assertEquals(100_000, pack.games[13]!!.numVotes)
        assertTrue(13 in pack.index.lookup("catan")[0].bggIds)
    }

    @Test
    fun `rejects a pack with the wrong version`() {
        val f = File(dir, "old.db")
        TestPacks.write(f, version = 1)
        assertThrows<IllegalArgumentException> { PackLoader.load(f.path) }
    }
}
