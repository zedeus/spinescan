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
            )
        }

        private fun Properties.require(key: String): String =
            getProperty(key) ?: error("Missing threshold: $key")
    }
}
