package spinescan.core.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackDownloaderTest {

    @TempDir
    lateinit var dir: File

    private class PackServer(
        var bytes: ByteArray,
        var sha256: String? = null,
        var status: Int = 200,
    ) : HttpTransport {
        override fun execute(request: HttpRequest): HttpResponse {
            val sha = sha256 ?: PackDownloader.sha256Hex(bytes)
            return HttpResponse(
                status,
                mapOf(GameDataApi.PACK_SHA256_HEADER to sha),
                bytes,
            )
        }
    }

    private fun downloader(server: PackServer) =
        PackDownloader(GameDataApi("https://api.test", "tok", server))

    @Test
    fun `downloads, verifies and installs a good pack`() {
        val target = File(dir, "pack.db")
        val pack = downloader(PackServer(TestPacks.gzipped())).download(target)
        assertEquals(3, pack.games.size)
        assertTrue(target.exists())
        assertTrue(pack.index.lookup("catan").isNotEmpty())
    }

    @Test
    fun `checksum mismatch rejects and leaves the old pack alone`() {
        val target = File(dir, "pack.db")
        target.writeText("old pack bytes")
        val server = PackServer(TestPacks.gzipped(), sha256 = "deadbeef")
        assertThrows<PackDownloader.PackVerificationException> {
            downloader(server).download(target)
        }
        assertEquals("old pack bytes", target.readText())
        assertFalse(File(dir, "pack.db.tmp").exists())
    }

    @Test
    fun `corrupt gzip rejected`() {
        val target = File(dir, "pack.db")
        val server = PackServer("not gzip at all".encodeToByteArray())
        assertThrows<PackDownloader.PackVerificationException> {
            downloader(server).download(target)
        }
        assertFalse(target.exists())
    }

    @Test
    fun `gzip of garbage that is not a pack rejected`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write("not sqlite".encodeToByteArray()) }
        val target = File(dir, "pack.db")
        assertThrows<PackDownloader.PackVerificationException> {
            downloader(PackServer(out.toByteArray())).download(target)
        }
        assertFalse(target.exists())
    }

    @Test
    fun `bad token surfaces as ApiException`() {
        val server = PackServer(TestPacks.gzipped(), status = 401)
        assertThrows<GameDataApi.ApiException> {
            downloader(server).download(File(dir, "pack.db"))
        }
    }
}
