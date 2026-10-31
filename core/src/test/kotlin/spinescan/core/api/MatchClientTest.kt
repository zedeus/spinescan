package spinescan.core.api

import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake server behind the HttpTransport interface. */
class FakeServer : HttpTransport {
    @kotlinx.serialization.Serializable
    private data class MatchRequest(val texts: List<String>)

    /** text -> candidates JSON fragment the server answers with */
    val answers = mutableMapOf<String, String>()
    val requests = mutableListOf<HttpRequest>()
    var offline = false
    var status = 200

    /** When set, every 200 answer is this raw body (e.g. a captive portal page). */
    var bodyOverride: String? = null

    private val json = kotlinx.serialization.json.Json

    override fun execute(request: HttpRequest): HttpResponse {
        if (offline) throw IOException("network unreachable")
        requests.add(request)
        if (status != 200) return HttpResponse(status, emptyMap(), "{}".encodeToByteArray())
        bodyOverride?.let { return HttpResponse(200, emptyMap(), it.encodeToByteArray()) }

        val results = batchTexts(request).joinToString(",") { t ->
            """{"text":"$t","candidates":[${answers[t] ?: ""}]}"""
        }
        return HttpResponse(200, emptyMap(), """{"results":[$results]}""".encodeToByteArray())
    }

    fun lastBatchTexts(): List<String> = batchTexts(requests.last())

    private fun batchTexts(request: HttpRequest): List<String> =
        json.decodeFromString<MatchRequest>(request.body!!.decodeToString()).texts
}

class MatchClientTest {

    private var now = 1_000L
    private val server = FakeServer()
    private val api = GameDataApi("https://api.test", "tok", server)
    private val client = MatchClient(api, batchMs = 300, retryMs = 5000, clock = { now })

    private val catan = """{"ids":[13],"name":"Catan","score":0.95}"""

    @Test
    fun `collects texts for the batch window before sending`() {
        server.answers["die siedler von catan"] = catan
        assertNull(client.lookup("die siedler von catan"))
        assertFalse(client.flush(), "must not send before the window passes")
        assertTrue(server.requests.isEmpty())

        now += 299
        assertFalse(client.flush())
        now += 1
        assertTrue(client.flush())
        assertEquals(1, server.requests.size)

        val hit = client.lookup("die siedler von catan")
        assertNotNull(hit)
        assertEquals(listOf(13), hit[0].ids)
    }

    @Test
    fun `batches multiple texts into one call and caches each`() {
        server.answers["a"] = catan
        client.lookup("a")
        client.lookup("b")
        client.lookup("a") // duplicate, queued once
        now += 300
        client.flush()
        assertEquals(1, server.requests.size)
        assertEquals(listOf("a", "b"), server.lastBatchTexts())

        // Cached: no further requests, unanswered text caches as empty
        assertEquals(listOf(13), client.lookup("a")!![0].ids)
        assertEquals(emptyList(), client.lookup("b"))
        now += 300
        client.flush()
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `never sends more than the cap in one call`() {
        for (i in 0 until 60) client.lookup("text $i")
        now += 300
        client.flush()
        assertEquals(50, server.lastBatchTexts().size)
        assertEquals(10, client.pendingCount)
    }

    @Test
    fun `offline keeps the queue and retries after backoff`() {
        server.offline = true
        client.lookup("a")
        now += 300
        assertFalse(client.flush())
        assertEquals(1, client.pendingCount)

        // Still backing off
        server.offline = false
        now += 1000
        assertFalse(client.flush())
        assertTrue(server.requests.isEmpty())

        // Past the retry window: sends
        now += 5000
        assertTrue(client.flush())
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `rejected token stops all traffic until reset`() {
        server.status = 401
        client.lookup("a")
        now += 300
        assertFalse(client.flush())
        assertTrue(client.authFailed)

        // No new texts queued, no sends
        client.lookup("b")
        now += 300
        assertFalse(client.flush())
        assertEquals(1, server.requests.size)

        server.status = 200
        client.resetAuth()
        client.lookup("b")
        now += 300
        assertTrue(client.flush())
    }

    @Test
    fun `blank text is never queued`() {
        assertNull(client.lookup(""))
        now += 300
        assertFalse(client.flush())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `overlong text is never queued`() {
        assertNull(client.lookup("x".repeat(GameDataApi.MAX_TEXT_LENGTH + 1)))
        now += 300
        assertFalse(client.flush())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `rejected batch is answered empty and never re-queued`() {
        server.status = 400
        client.lookup("a")
        now += 300
        assertFalse(client.flush())
        assertEquals(1, server.requests.size)

        // The failed text is cached empty, so looking it up again
        // does not re-queue it into an endless 400 loop.
        assertEquals(emptyList(), client.lookup("a"))
        assertEquals(0, client.pendingCount)
        now += 300
        assertFalse(client.flush())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `server error keeps the queue and backs off`() {
        server.status = 500
        client.lookup("a")
        now += 300
        assertFalse(client.flush())
        assertEquals(1, client.pendingCount)
        assertEquals(1, server.requests.size)

        // Within the backoff window nothing is sent, even with the text re-looked-up.
        server.status = 200
        server.answers["a"] = catan
        assertNull(client.lookup("a"))
        now += 300
        assertFalse(client.flush())
        assertEquals(1, server.requests.size)

        now += 5000
        assertTrue(client.flush())
        assertEquals(listOf(13), client.lookup("a")!![0].ids)
    }

    @Test
    fun `malformed 200 response backs off instead of crashing`() {
        server.bodyOverride = "<html>hotel wifi login</html>"
        client.lookup("a")
        now += 300
        assertFalse(client.flush())
        assertEquals(1, client.pendingCount)

        server.bodyOverride = null
        server.answers["a"] = catan
        now += 5000
        assertTrue(client.flush())
        assertEquals(listOf(13), client.lookup("a")!![0].ids)
    }
}
