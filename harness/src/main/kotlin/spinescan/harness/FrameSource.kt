package spinescan.harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A single frame with its image data and metadata.
 */
data class Frame(
    val image: BufferedImage,
    val index: Int,
    val timestampMs: Long,
    val sourcePath: String,
) {
    /** Extract RGB byte array (3 bytes per pixel, row-major). */
    fun toRgbBytes(): ByteArray {
        val w = image.width
        val h = image.height
        val rgb = ByteArray(w * h * 3)
        var offset = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = image.getRGB(x, y)
                rgb[offset++] = ((pixel shr 16) and 0xFF).toByte()
                rgb[offset++] = ((pixel shr 8) and 0xFF).toByte()
                rgb[offset++] = (pixel and 0xFF).toByte()
            }
        }
        return rgb
    }
}

/**
 * Loads frames from photos or videos.
 *
 * Photos are loaded directly. Videos are extracted via ffmpeg into
 * a cache directory, one PNG per frame with timestamp metadata.
 */
class FrameSource {

    /**
     * Load frames from a path (file or directory).
     *
     * - Single image file: returns one frame
     * - Directory: loads all images sorted by name
     * - Video file: extracts frames via ffmpeg (cached)
     *
     * @param maxDimension if > 0, downscale frames so neither side exceeds this
     */
    fun load(
        path: String,
        maxDimension: Int = 0,
        cacheDir: String? = null,
    ): List<Frame> {
        val file = File(path)
        require(file.exists()) { "Path does not exist: $path" }

        return when {
            file.isDirectory -> loadDirectory(file, maxDimension)
            isImage(file) -> listOf(loadImage(file, 0, maxDimension))
            isVideo(file) -> loadVideo(file, maxDimension, cacheDir)
            else -> error("Unsupported file type: ${file.name}")
        }
    }

    private fun loadDirectory(dir: File, maxDimension: Int): List<Frame> {
        val files = dir.listFiles()
            ?.filter { isImage(it) }
            ?.sortedBy { it.name }
            ?: return emptyList()

        return files.mapIndexed { index, file -> loadImage(file, index, maxDimension) }
    }

    private fun loadImage(file: File, index: Int, maxDimension: Int): Frame {
        var image = ImageIO.read(file) ?: error("Cannot read image: ${file.path}")
        if (maxDimension > 0) {
            image = downscale(image, maxDimension)
        }
        return Frame(
            image = image,
            index = index,
            timestampMs = 0,
            sourcePath = file.path,
        )
    }

    private fun loadVideo(file: File, maxDimension: Int, cacheDir: String?): List<Frame> {
        // Check ffmpeg
        try {
            ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start().waitFor()
        } catch (e: Exception) {
            error("ffmpeg not found. Install ffmpeg to process video files.")
        }

        val dir = File(cacheDir ?: "${file.parent}/.frames_${file.nameWithoutExtension}")

        // Check if already extracted
        val stampFile = File(dir, ".extracted")
        if (!stampFile.exists() || stampFile.lastModified() < file.lastModified()) {
            dir.mkdirs()

            // Extract frames with timestamps in filename
            val scaleFilter = if (maxDimension > 0) {
                ",scale='min($maxDimension,iw)':'min($maxDimension,ih)':force_original_aspect_ratio=decrease"
            } else ""

            val pb = ProcessBuilder(
                "ffmpeg", "-y", "-i", file.path,
                "-vsync", "0",
                "-vf", "showinfo$scaleFilter",
                "-frame_pts", "1",
                "${dir.path}/frame_%06d.png"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()

            // Parse timestamps from ffmpeg output
            val timestamps = mutableMapOf<Int, Long>()
            process.inputStream.bufferedReader().forEachLine { line ->
                // showinfo filter outputs: n: N pts: P pts_time: T.TT
                val match = Regex("""n:\s*(\d+)\s+.*pts_time:\s*([\d.]+)""").find(line)
                if (match != null) {
                    val n = match.groupValues[1].toInt()
                    val pts = (match.groupValues[2].toDouble() * 1000).toLong()
                    timestamps[n] = pts
                }
            }
            process.waitFor()

            // Write timestamp mapping
            File(dir, "timestamps.txt").writeText(
                timestamps.entries.sortedBy { it.key }
                    .joinToString("\n") { "${it.key}\t${it.value}" }
            )
            stampFile.writeText(file.path)
        }

        // Load timestamps
        val tsFile = File(dir, "timestamps.txt")
        val timestamps = if (tsFile.exists()) {
            tsFile.readLines().associate { line ->
                val parts = line.split("\t")
                parts[0].toInt() to parts[1].toLong()
            }
        } else emptyMap()

        // Load extracted frames
        val frameFiles = dir.listFiles()
            ?.filter { it.name.startsWith("frame_") && it.name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?: return emptyList()

        return frameFiles.mapIndexed { index, f ->
            var image = ImageIO.read(f) ?: return@mapIndexed null
            if (maxDimension > 0) {
                image = downscale(image, maxDimension)
            }
            Frame(
                image = image,
                index = index,
                timestampMs = timestamps[index] ?: 0L,
                sourcePath = f.path,
            )
        }.filterNotNull()
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm")

        fun isImage(file: File): Boolean =
            file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS

        fun isVideo(file: File): Boolean =
            file.isFile && file.extension.lowercase() in VIDEO_EXTENSIONS

        fun downscale(image: BufferedImage, maxDimension: Int): BufferedImage {
            val w = image.width
            val h = image.height
            if (w <= maxDimension && h <= maxDimension) return image

            val scale = maxDimension.toDouble() / maxOf(w, h)
            val nw = (w * scale).toInt()
            val nh = (h * scale).toInt()

            val scaled = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(image, 0, 0, nw, nh, null)
            g.dispose()
            return scaled
        }
    }
}
