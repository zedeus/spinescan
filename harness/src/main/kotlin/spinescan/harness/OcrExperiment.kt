package spinescan.harness

import spinescan.core.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import javax.imageio.ImageIO

/**
 * OCR preprocessing experiments.
 * Tries different image transformations on each box crop and reports
 * which produce the best matches.
 */
class OcrExperiment(
    private val ocrEngine: PpOcrEngine,
    private val index: TrigramIndex,
) {
    data class ReadResult(
        val strategy: String,
        val text: String,
        val matchName: String?,
        val matchScore: Double,
        val popularity: Int,
    )

    /**
     * Try multiple preprocessing strategies on a crop and return all results.
     */
    fun tryAllStrategies(crop: BufferedImage): List<ReadResult> {
        val results = mutableListOf<ReadResult>()

        // Strategy 1: raw crop
        results.addAll(readWith("raw", crop))

        // Strategy 2: grayscale
        results.addAll(readWith("gray", toGrayscale(crop)))

        // Strategy 3: sharpened
        results.addAll(readWith("sharp", sharpen(crop)))

        // Strategy 4: high contrast
        results.addAll(readWith("contrast", enhanceContrast(crop, 1.5)))

        // Strategy 5: grayscale + sharpen
        results.addAll(readWith("gray+sharp", sharpen(toGrayscale(crop))))

        // Strategy 6: grayscale + high contrast
        results.addAll(readWith("gray+contrast", enhanceContrast(toGrayscale(crop), 1.8)))

        // Strategy 7: binarize (Otsu-like threshold)
        results.addAll(readWith("binary", binarize(crop)))

        // Strategy 8: invert (for light text on dark background)
        results.addAll(readWith("invert", invert(crop)))

        // Strategy 9: grayscale + invert
        results.addAll(readWith("gray+invert", invert(toGrayscale(crop))))

        // Strategy 10: 90° rotation (for vertical text read as horizontal)
        results.addAll(readWith("rot90", rotate90(crop)))

        // Strategy 11: 270° rotation
        results.addAll(readWith("rot270", rotate270(crop)))

        // Strategy 12: 2x upscale + sharpen
        val upscaled = upscale(crop, 2.0)
        results.addAll(readWith("2x+sharp", sharpen(upscaled)))

        // Strategy 13: 3x upscale
        results.addAll(readWith("3x", upscale(crop, 3.0)))

        return results
    }

    private fun readWith(strategy: String, image: BufferedImage): List<ReadResult> {
        val lines = ocrEngine.detectFromImage(image)
        if (lines.isEmpty()) return emptyList()

        val allText = lines.joinToString(" ") { it.text }.trim()
        if (allText.length < 3) return emptyList()

        val candidates = TextMatcher.matchAll(allText, index)
        val top = candidates.firstOrNull()

        return listOf(ReadResult(
            strategy = strategy,
            text = allText,
            matchName = top?.name,
            matchScore = top?.score ?: 0.0,
            popularity = top?.popularity ?: 0,
        ))
    }

    // --- Image transformations ---

    fun toGrayscale(img: BufferedImage): BufferedImage {
        val gray = BufferedImage(img.width, img.height, BufferedImage.TYPE_BYTE_GRAY)
        val g = gray.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        // Convert back to RGB for OCR compatibility
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g2 = rgb.createGraphics()
        g2.drawImage(gray, 0, 0, null)
        g2.dispose()
        return rgb
    }

    fun sharpen(img: BufferedImage): BufferedImage {
        val kernel = Kernel(3, 3, floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f,
        ))
        val op = ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null)
        val result = BufferedImage(img.width, img.height, img.type)
        op.filter(img, result)
        return result
    }

    fun enhanceContrast(img: BufferedImage, factor: Double): BufferedImage {
        val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = clamp(((((rgb shr 16) and 0xFF) - 128) * factor + 128).toInt())
                val g = clamp(((((rgb shr 8) and 0xFF) - 128) * factor + 128).toInt())
                val b = clamp((((rgb and 0xFF) - 128) * factor + 128).toInt())
                result.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        return result
    }

    fun binarize(img: BufferedImage): BufferedImage {
        val gray = toGrayscale(img)
        // Simple Otsu-like: compute mean, threshold at mean
        var sum = 0L
        val total = img.width * img.height
        for (y in 0 until gray.height) {
            for (x in 0 until gray.width) {
                sum += gray.getRGB(x, y) and 0xFF
            }
        }
        val threshold = (sum / total).toInt()

        val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until gray.height) {
            for (x in 0 until gray.width) {
                val v = gray.getRGB(x, y) and 0xFF
                val c = if (v > threshold) 0xFFFFFF else 0x000000
                result.setRGB(x, y, c)
            }
        }
        return result
    }

    fun invert(img: BufferedImage): BufferedImage {
        val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val inv = (0xFF - ((rgb shr 16) and 0xFF) shl 16) or
                    (0xFF - ((rgb shr 8) and 0xFF) shl 8) or
                    (0xFF - (rgb and 0xFF))
                result.setRGB(x, y, inv)
            }
        }
        return result
    }

    fun rotate90(img: BufferedImage): BufferedImage {
        val result = BufferedImage(img.height, img.width, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                result.setRGB(img.height - 1 - y, x, img.getRGB(x, y))
            }
        }
        return result
    }

    fun rotate270(img: BufferedImage): BufferedImage {
        val result = BufferedImage(img.height, img.width, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                result.setRGB(y, img.width - 1 - x, img.getRGB(x, y))
            }
        }
        return result
    }

    fun upscale(img: BufferedImage, factor: Double): BufferedImage {
        val nw = (img.width * factor).toInt()
        val nh = (img.height * factor).toInt()
        val result = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = result.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
        g.drawImage(img, 0, 0, nw, nh, null)
        g.dispose()
        return result
    }

    /**
     * CLAHE-like adaptive contrast enhancement.
     * Divides the image into tiles and equalizes contrast within each tile,
     * then interpolates between tiles. Much better than global contrast for
     * photos with uneven lighting.
     */
    fun adaptiveContrast(img: BufferedImage, tileSize: Int = 64, clipLimit: Double = 2.0): BufferedImage {
        val gray = toGrayscale(img)
        val w = img.width
        val h = img.height
        val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        // Simple approach: apply local histogram equalization
        for (ty in 0 until h step tileSize) {
            for (tx in 0 until w step tileSize) {
                val tw = minOf(tileSize, w - tx)
                val th = minOf(tileSize, h - ty)

                // Build histogram for this tile
                val hist = IntArray(256)
                for (y in ty until ty + th) {
                    for (x in tx until tx + tw) {
                        hist[gray.getRGB(x, y) and 0xFF]++
                    }
                }

                // Clip histogram (CLAHE) and redistribute
                val pixels = tw * th
                val limit = (clipLimit * pixels / 256).toInt()
                var excess = 0
                for (i in 0 until 256) {
                    if (hist[i] > limit) {
                        excess += hist[i] - limit
                        hist[i] = limit
                    }
                }
                val addPerBin = excess / 256
                for (i in 0 until 256) hist[i] += addPerBin

                // Build CDF
                val cdf = IntArray(256)
                cdf[0] = hist[0]
                for (i in 1 until 256) cdf[i] = cdf[i - 1] + hist[i]
                val cdfMin = cdf.firstOrNull { it > 0 } ?: 0
                val total = cdf[255]

                // Apply equalization
                for (y in ty until ty + th) {
                    for (x in tx until tx + tw) {
                        val v = gray.getRGB(x, y) and 0xFF
                        val eq = if (total > cdfMin) {
                            ((cdf[v] - cdfMin).toDouble() / (total - cdfMin) * 255).toInt()
                        } else v
                        val c = clamp(eq)
                        result.setRGB(x, y, (c shl 16) or (c shl 8) or c)
                    }
                }
            }
        }
        return result
    }

    private fun clamp(v: Int): Int = maxOf(0, minOf(255, v))
}
