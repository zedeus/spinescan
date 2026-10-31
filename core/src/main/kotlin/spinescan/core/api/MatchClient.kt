package spinescan.core.api

import java.io.IOException

/**
 * Batches pack misses into API match calls.
 *
 * The sweep calls [lookup] per unmatched box text and [flush] once per frame.
 * Texts collect for `apiBatchMs` before a batch goes out; results land in a
 * per-text cache that later [lookup] calls hit. Offline failures and server
 * errors keep the queue and back off for `apiRetryMs`; a rejected batch is
 * answered empty so it is never re-queued; a rejected token stops all
 * traffic until [resetAuth].
 */
class MatchClient(
    private val api: GameDataApi,
    private val batchMs: Long,
    private val retryMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = mutableMapOf<String, List<GameDataApi.MatchCandidate>>()
    private val queue = LinkedHashSet<String>()
    private var sendAt: Long? = null
    private var offlineUntil = 0L

    /** The token was rejected; no further requests go out until [resetAuth]. */
    var authFailed = false
        private set

    /**
     * Cached API candidates for a text, or null if not fetched yet
     * (the text is queued for the next batch).
     */
    fun lookup(text: String): List<GameDataApi.MatchCandidate>? {
        cache[text]?.let { return it }
        // Overlong text would 400 the whole batch server-side; never queue it.
        val sendable = text.isNotBlank() && text.length <= GameDataApi.MAX_TEXT_LENGTH
        if (!authFailed && sendable && text !in queue) {
            queue.add(text)
            if (sendAt == null) sendAt = clock() + batchMs
        }
        return null
    }

    /**
     * Send the pending batch if its collection window has passed.
     * Call once per frame. Returns true if a batch was sent successfully.
     */
    fun flush(): Boolean {
        val now = clock()
        val due = sendAt ?: return false
        if (authFailed || now < due || now < offlineUntil) return false

        val batch = queue.take(GameDataApi.MAX_TEXTS)
        return try {
            val results = api.match(batch)
            for (r in results) cache[r.text] = r.candidates
            // Anything the server didn't answer for stays answered-empty,
            // not re-queued forever.
            for (t in batch) cache.putIfAbsent(t, emptyList())
            queue.removeAll(batch.toSet())
            sendAt = if (queue.isEmpty()) null else now + batchMs
            true
        } catch (e: GameDataApi.ApiException) {
            when (e.kind) {
                GameDataApi.ApiException.Kind.BAD_TOKEN -> {
                    authFailed = true
                    queue.clear()
                    sendAt = null
                }
                GameDataApi.ApiException.Kind.BAD_REQUEST -> {
                    // The server rejected this batch: answer it empty so later
                    // lookups don't re-queue the same texts and loop on it.
                    for (t in batch) cache.putIfAbsent(t, emptyList())
                    queue.removeAll(batch.toSet())
                    sendAt = if (queue.isEmpty()) null else now + batchMs
                }
                GameDataApi.ApiException.Kind.SERVER ->
                    // Server trouble (5xx, malformed response): back off like offline.
                    offlineUntil = now + retryMs
            }
            false
        } catch (e: IOException) {
            // Offline: keep the queue, retry later.
            offlineUntil = now + retryMs
            false
        }
    }

    /** Clear the auth failure after the user fixes the token. */
    fun resetAuth() {
        authFailed = false
    }

    val pendingCount: Int get() = queue.size
}
