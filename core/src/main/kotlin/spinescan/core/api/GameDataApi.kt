package spinescan.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Client for the private game-data API (Cloudflare Worker + D1).
 *
 * Only what the phone needs during a sweep: fuzzy match for texts the local
 * pack missed, and the pack download. Full game records (`GET /v1/games`)
 * are fetched when the library needs them, not here.
 */
class GameDataApi(
    baseUrl: String,
    private val token: String,
    private val transport: HttpTransport,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MatchCandidate(val ids: List<Int>, val name: String, val score: Double)

    @Serializable
    data class MatchResult(val text: String, val candidates: List<MatchCandidate>)

    @Serializable
    private data class MatchRequest(val texts: List<String>)

    @Serializable
    private data class MatchResponse(val results: List<MatchResult>)

    /** The sha256 of the gzipped pack doubles as its content version. */
    class PackDownload(val sha256: String, val gzipBytes: ByteArray)

    class ApiException(val kind: Kind, message: String) : Exception(message) {
        enum class Kind { BAD_TOKEN, BAD_REQUEST, SERVER }
    }

    /** Fuzzy-match up to [MAX_TEXTS] normalised texts. Throws [ApiException]
     *  on API errors and [java.io.IOException] when offline. */
    fun match(texts: List<String>): List<MatchResult> {
        require(texts.size <= MAX_TEXTS) { "At most $MAX_TEXTS texts per call" }
        val body = json.encodeToString(MatchRequest(texts)).encodeToByteArray()
        val resp = transport.execute(
            HttpRequest(
                method = "POST",
                url = "$baseUrl/v1/match",
                headers = authHeaders() + ("Content-Type" to "application/json"),
                body = body,
            )
        )
        checkStatus(resp)
        return try {
            json.decodeFromString<MatchResponse>(resp.body.decodeToString()).results
        } catch (e: kotlinx.serialization.SerializationException) {
            // A 200 that isn't our JSON (captive portal, proxy error page).
            throw ApiException(ApiException.Kind.SERVER, "Malformed response")
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiException.Kind.SERVER, "Malformed response")
        }
    }

    /** Download the compressed pack with its checksum header. */
    fun downloadPack(): PackDownload {
        val resp = transport.execute(
            HttpRequest(method = "GET", url = "$baseUrl/v1/pack", headers = authHeaders())
        )
        checkStatus(resp)
        return PackDownload(
            sha256 = resp.header(PACK_SHA256_HEADER)
                ?: throw ApiException(ApiException.Kind.SERVER, "Missing $PACK_SHA256_HEADER"),
            gzipBytes = resp.body,
        )
    }

    private fun authHeaders() = mapOf("Authorization" to "Bearer $token")

    private fun checkStatus(resp: HttpResponse) {
        when {
            resp.status == 401 || resp.status == 403 ->
                throw ApiException(ApiException.Kind.BAD_TOKEN, "Rejected token (${resp.status})")
            resp.status in 400..499 ->
                throw ApiException(ApiException.Kind.BAD_REQUEST, "Bad request (${resp.status})")
            resp.status >= 500 ->
                throw ApiException(ApiException.Kind.SERVER, "Server error (${resp.status})")
        }
    }

    companion object {
        /** Server-side cap on texts per match call; the client never exceeds it. */
        const val MAX_TEXTS = 50

        /** Server-side cap on characters per text; longer texts 400 the batch. */
        const val MAX_TEXT_LENGTH = 200
        const val PACK_SHA256_HEADER = "X-Pack-Sha256"
    }
}
