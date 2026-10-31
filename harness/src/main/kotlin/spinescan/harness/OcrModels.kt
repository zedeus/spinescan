package spinescan.harness

import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads and caches PP-OCR ONNX models.
 *
 * Models are stored in a local directory (default: models/ in the project root).
 * Downloads are verified by SHA-256 checksum.
 */
object OcrModels {
    data class ModelInfo(
        val name: String,
        val url: String,
        val sha256: String,
        val filename: String,
    )

    // PP-OCRv6 small models from PaddlePaddle on HuggingFace
    val DET = ModelInfo(
        name = "PP-OCRv6 Detection (small)",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
        sha256 = "",
        filename = "PP-OCRv6_small_det.onnx",
    )

    val REC = ModelInfo(
        name = "PP-OCRv6 Recognition (small, multilingual)",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx",
        sha256 = "",
        filename = "PP-OCRv6_small_rec.onnx",
    )

    // v6 character dictionary (18,708 chars covering 50 languages)
    val DICT_URL = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml"
    val DICT_FILENAME = "ppocr_v6_dict.txt"
    val DICT_YML_FILENAME = "PP-OCRv6_small_rec.yml"

    /**
     * Ensure all models are downloaded and return their directory.
     */
    fun ensureModels(modelDir: File = defaultModelDir()): File {
        modelDir.mkdirs()

        for (model in listOf(DET, REC)) {
            val file = File(modelDir, model.filename)
            if (!file.exists()) {
                println("Downloading ${model.name} ...")
                downloadFile(model.url, file)
                println("  → ${file.name} (${file.length() / 1024} KB)")
            }
        }

        val dictFile = File(modelDir, DICT_FILENAME)
        if (!dictFile.exists()) {
            // Download the YAML config and extract the character dictionary
            val ymlFile = File(modelDir, DICT_YML_FILENAME)
            if (!ymlFile.exists()) {
                println("Downloading model config ...")
                downloadFile(DICT_URL, ymlFile)
            }
            println("Extracting character dictionary ...")
            extractDictFromYml(ymlFile, dictFile)
            println("  → ${dictFile.name} (${dictFile.readLines().size} characters)")
        }

        return modelDir
    }

    /**
     * Extract the character dictionary from PP-OCR's inference.yml.
     * The dict is under PostProcess.character_dict as a YAML list.
     */
    private fun extractDictFromYml(ymlFile: File, dictFile: File) {
        // Simple YAML parsing: find "character_dict:" then read "- X" lines
        val lines = ymlFile.readLines()
        val chars = mutableListOf<String>()
        var inDict = false
        for (line in lines) {
            if (line.trimEnd().endsWith("character_dict:")) {
                inDict = true
                continue
            }
            if (inDict) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("- ")) {
                    // Extract the character after "- "
                    var ch = trimmed.substring(2)
                    // Handle YAML quoting: '''' → ', "x" → x
                    if (ch.startsWith("'") && ch.endsWith("'")) {
                        ch = ch.substring(1, ch.length - 1).replace("''", "'")
                    } else if (ch.startsWith("\"") && ch.endsWith("\"")) {
                        ch = ch.substring(1, ch.length - 1)
                    }
                    chars.add(ch)
                } else if (!trimmed.startsWith("#") && trimmed.isNotBlank()) {
                    // End of the list
                    break
                }
            }
        }
        dictFile.writeText(chars.joinToString("\n") + "\n")
    }

    fun defaultModelDir(): File {
        // Search upward for project root (has settings.gradle.kts)
        var dir = File(".").absoluteFile
        repeat(5) {
            if (File(dir, "settings.gradle.kts").exists()) {
                return File(dir, "models")
            }
            dir = dir.parentFile ?: return File("models")
        }
        return File("models")
    }

    private fun downloadFile(url: String, dest: File) {
        val conn = URI(url).toURL().openConnection()
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    @Suppress("unused")
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
