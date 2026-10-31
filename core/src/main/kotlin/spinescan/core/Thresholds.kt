package spinescan.core

import java.util.Properties

/**
 * Scoring thresholds loaded from the single config file.
 * All threshold values live here; no magic numbers elsewhere.
 */
data class Thresholds(
    val greenScore: Double,
    val greenLead: Double,
    val greenFrames: Int,
    val greenSingle: Double,
    val orangeFloor: Double,
    /** How long the API client collects miss texts before sending a batch. */
    val apiBatchMs: Long,
    /** How long the API client waits after a network failure before retrying. */
    val apiRetryMs: Long,
    /** HTTP connect/read timeout for API calls. */
    val apiTimeoutMs: Long,
) {
    companion object {
        private const val RESOURCE = "/thresholds.properties"

        fun load(): Thresholds = load(
            Thresholds::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing resource: $RESOURCE")
        )

        fun load(input: java.io.InputStream): Thresholds {
            val props = Properties()
            input.use { props.load(it) }
            return Thresholds(
                greenScore = props.require("green.score").toDouble(),
                greenLead = props.require("green.lead").toDouble(),
                greenFrames = props.require("green.frames").toInt(),
                greenSingle = props.require("green.single").toDouble(),
                orangeFloor = props.require("orange.floor").toDouble(),
                apiBatchMs = props.require("api.batch.ms").toLong(),
                apiRetryMs = props.require("api.retry.ms").toLong(),
                apiTimeoutMs = props.require("api.timeout.ms").toLong(),
            )
        }

        private fun Properties.require(key: String): String =
            getProperty(key) ?: error("Missing threshold: $key")
    }
}
