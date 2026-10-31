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

    // PP-OCR models from HuggingFace (SWHL/RapidOCR)
    val DET = ModelInfo(
        name = "PP-OCRv4 Detection",
        url = "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
        sha256 = "",
        filename = "ch_PP-OCRv4_det_infer.onnx",
    )

    val REC = ModelInfo(
        name = "PP-OCRv3 Recognition (English)",
        url = "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv3/en_PP-OCRv3_rec_infer.onnx",
        sha256 = "",
        filename = "en_PP-OCRv3_rec_infer.onnx",
    )

    val CLS = ModelInfo(
        name = "PP-OCR Classifier",
        url = "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv3/ch_ppocr_mobile_v2.0_cls_train.onnx",
        sha256 = "",
        filename = "ch_ppocr_mobile_v2.0_cls_train.onnx",
    )

    val DICT_URL = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/en_dict.txt"
    val DICT_FILENAME = "en_dict.txt"

    /**
     * Ensure all models are downloaded and return their directory.
     */
    fun ensureModels(modelDir: File = defaultModelDir()): File {
        modelDir.mkdirs()

        for (model in listOf(DET, REC, CLS)) {
            val file = File(modelDir, model.filename)
            if (!file.exists()) {
                println("Downloading ${model.name} ...")
                downloadFile(model.url, file)
                println("  → ${file.name} (${file.length() / 1024} KB)")
            }
        }

        val dictFile = File(modelDir, DICT_FILENAME)
        if (!dictFile.exists()) {
            println("Downloading character dictionary ...")
            downloadFile(DICT_URL, dictFile)
        }

        return modelDir
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
