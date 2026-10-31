package spinescan.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import spinescan.core.Normalize
import java.io.File

/**
 * Ground truth labels for eval.
 *
 * Each photo has a JSON label file listing the games visible in it.
 * Labels are independent of pipeline output — they are drafted by reading
 * frames and approved by the user on the checklist page.
 */
@Serializable
data class LabelFile(
    val photo: String,
    val set: String = "tune",
    val draft: Boolean = true,
    val games: List<GameLabel>,
)

@Serializable
data class GameLabel(
    val name: String,
    val legible: Boolean = true,
)

object Labels {
    private val json = Json { ignoreUnknownKeys = true }

    /** Load all label files from a directory. */
    fun loadAll(labelsDir: File): List<LabelFile> {
        if (!labelsDir.exists()) return emptyList()
        return labelsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedBy { it.name }
            ?.map { load(it) }
            ?: emptyList()
    }

    fun load(file: File): LabelFile = json.decodeFromString(file.readText())

    /** Save a label file (for accept/update). */
    fun save(file: File, label: LabelFile) {
        val prettyJson = Json { prettyPrint = true }
        file.writeText(prettyJson.encodeToString(LabelFile.serializer(), label))
    }

    /**
     * Build a set of normalised game names from a label file,
     * for matching against pipeline output.
     */
    fun normalisedNames(label: LabelFile): Set<String> =
        label.games.map { Normalize.normalize(it.name) }.toSet()

    /** Only legible games. */
    fun legibleNames(label: LabelFile): Set<String> =
        label.games.filter { it.legible }.map { Normalize.normalize(it.name) }.toSet()
}
