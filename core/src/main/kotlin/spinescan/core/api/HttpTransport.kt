package spinescan.core.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Minimal HTTP abstraction so the API client can be tested against a fake
 * server. The real implementation uses HttpURLConnection (works on JVM and
 * Android alike).
 */
interface HttpTransport {
    /** Throws [IOException] when the network is unreachable (offline). */
    fun execute(request: HttpRequest): HttpResponse
}

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

class UrlConnectionTransport(
    /** Connect and read timeout; without one a dead connection hangs forever. */
    private val timeoutMs: Long,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.requestMethod = request.method
            for ((k, v) in request.headers) conn.setRequestProperty(k, v)
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body) }
            }
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.joinToString(",") }
            return HttpResponse(status, headers, body)
        } finally {
            conn.disconnect()
        }
    }
}
