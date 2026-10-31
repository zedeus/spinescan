package spinescan.core.api

import spinescan.core.PackLoader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads the pack, verifies it, and installs it atomically.
 *
 * On any failure (checksum mismatch, corrupt gzip, pack that doesn't load)
 * the existing pack file is left untouched and a [PackVerificationException]
 * is thrown. On success the new file replaces [target] and the loaded pack
 * is returned, ready to swap into the pipeline.
 */
class PackDownloader(private val api: GameDataApi) {

    class PackVerificationException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    fun download(target: File): PackLoader.Pack {
        val dl = api.downloadPack()

        val digest = sha256Hex(dl.gzipBytes)
        if (!digest.equals(dl.sha256, ignoreCase = true)) {
            throw PackVerificationException(
                "Pack checksum mismatch: expected ${dl.sha256}, got $digest"
            )
        }

        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            try {
                tmp.outputStream().use { out ->
                    GZIPInputStream(dl.gzipBytes.inputStream()).copyTo(out)
                }
            } catch (e: IOException) {
                throw PackVerificationException("Pack is not valid gzip", e)
            }

            val pack = try {
                PackLoader.load(tmp.path)
            } catch (e: Exception) {
                throw PackVerificationException("Downloaded pack failed to load", e)
            }

            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            return pack
        } finally {
            tmp.delete()
        }
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
