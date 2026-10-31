package spinescan.harness

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import spinescan.core.OcrEngine
import spinescan.core.Rect
import spinescan.core.TextLine
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv4 text detection and recognition via ONNX Runtime.
 *
 * Uses the same PP-OCR models on desktop and phone (via onnxruntime / onnxruntime-android).
 * The pre/post-processing is written in Kotlin, shared across both.
 *
 * Pipeline: Detection (DBNet) → Crop text regions → Recognition (CRNN + CTC)
 */
class PpOcrEngine(modelDir: File) : OcrEngine, Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val dictionary: List<String>

    // Detection parameters
    private val detMaxSide = 960
    private val detThreshold = 0.3f
    private val boxThreshold = 0.5f
    private val minBoxSize = 5

    // Recognition parameters — PP-OCRv3 English uses height 48
    private val recImgH = 48
    private val recImgW = 320

    // ImageNet normalization
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    init {
        val detPath = File(modelDir, OcrModels.DET.filename).absolutePath
        val recPath = File(modelDir, OcrModels.REC.filename).absolutePath
        val dictPath = File(modelDir, OcrModels.DICT_FILENAME).absolutePath

        require(File(detPath).exists()) { "Detection model not found: $detPath" }
        require(File(recPath).exists()) { "Recognition model not found: $recPath" }
        require(File(dictPath).exists()) { "Dictionary not found: $dictPath" }

        detSession = env.createSession(detPath)
        recSession = env.createSession(recPath)
        dictionary = File(dictPath).readLines().filter { it.isNotEmpty() }
    }

    override fun detect(imageData: ByteArray, width: Int, height: Int): List<TextLine> {
        val image = rgbBytesToImage(imageData, width, height)
        return detectFromImage(image)
    }

    fun detectFromImage(image: BufferedImage): List<TextLine> {
        // 1. Run text detection
        val boxes = runDetection(image)
        if (boxes.isEmpty()) return emptyList()

        // 2. Crop and recognize each box
        return boxes.mapNotNull { box ->
            val crop = cropTextRegion(image, box)
            if (crop == null) return@mapNotNull null
            val (text, confidence) = runRecognition(crop)
            if (text.isBlank()) return@mapNotNull null

            val w = box.maxX - box.minX
            val h = box.maxY - box.minY
            TextLine(
                box = Rect(box.minX, box.minY, w, h),
                text = text,
                confidence = confidence,
                isVertical = h > w * 1.5,
            )
        }
    }

    fun detectFromFile(path: String): List<TextLine> {
        val image = ImageIO.read(File(path)) ?: error("Cannot read image: $path")
        return detectFromImage(image)
    }

    // --- Detection ---

    private data class DetBox(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private fun runDetection(image: BufferedImage): List<DetBox> {
        val origW = image.width
        val origH = image.height

        // Resize so max side <= detMaxSide, dimensions multiple of 32
        val scale = min(detMaxSide.toFloat() / max(origW, origH), 1f)
        val resizedW = roundTo32((origW * scale).roundToInt())
        val resizedH = roundTo32((origH * scale).roundToInt())

        val resized = resizeImage(image, resizedW, resizedH)

        // Prepare input tensor: [1, 3, H, W], normalized
        val inputData = imageToNormalizedCHW(resized)
        val shape = longArrayOf(1, 3, resizedH.toLong(), resizedW.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)

        // Run detection
        val inputName = detSession.inputNames.first()
        val results = detSession.run(mapOf(inputName to tensor))
        val outputTensor = results[0].value

        // Parse probability map
        @Suppress("UNCHECKED_CAST")
        val probMap = when (outputTensor) {
            is Array<*> -> {
                // Shape: [1][1][H][W] as float[][][][] or float[][][]
                val batch = outputTensor as Array<Array<Array<FloatArray>>>
                batch[0][0]
            }
            else -> {
                tensor.close()
                results.close()
                return emptyList()
            }
        }

        tensor.close()
        results.close()

        // Threshold and find connected components
        val scaleX = origW.toFloat() / resizedW
        val scaleY = origH.toFloat() / resizedH

        return findBoxes(probMap, resizedW, resizedH, scaleX, scaleY, origW, origH)
    }

    private fun findBoxes(
        probMap: Array<FloatArray>,
        mapW: Int,
        mapH: Int,
        scaleX: Float,
        scaleY: Float,
        origW: Int,
        origH: Int,
    ): List<DetBox> {
        // Binary threshold
        val binary = Array(mapH) { y -> BooleanArray(mapW) { x -> probMap[y][x] > detThreshold } }

        // Simple connected-component labeling (4-connected)
        val labels = Array(mapH) { IntArray(mapW) }
        var nextLabel = 1
        val equiv = mutableMapOf<Int, Int>()

        fun findRoot(l: Int): Int {
            var x = l
            while (equiv[x] != null && equiv[x] != x) x = equiv[x]!!
            return x
        }

        for (y in 0 until mapH) {
            for (x in 0 until mapW) {
                if (!binary[y][x]) continue

                val above = if (y > 0 && binary[y - 1][x]) labels[y - 1][x] else 0
                val left = if (x > 0 && binary[y][x - 1]) labels[y][x - 1] else 0

                when {
                    above == 0 && left == 0 -> {
                        labels[y][x] = nextLabel++
                    }
                    above != 0 && left == 0 -> labels[y][x] = above
                    above == 0 && left != 0 -> labels[y][x] = left
                    else -> {
                        val ra = findRoot(above)
                        val rl = findRoot(left)
                        labels[y][x] = min(ra, rl)
                        if (ra != rl) equiv[max(ra, rl)] = min(ra, rl)
                    }
                }
            }
        }

        // Collect bounding boxes per component
        data class Bounds(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var score: Float, var count: Int)
        val components = mutableMapOf<Int, Bounds>()

        for (y in 0 until mapH) {
            for (x in 0 until mapW) {
                if (labels[y][x] == 0) continue
                val root = findRoot(labels[y][x])
                val b = components.getOrPut(root) { Bounds(x, y, x, y, 0f, 0) }
                b.minX = min(b.minX, x)
                b.minY = min(b.minY, y)
                b.maxX = max(b.maxX, x)
                b.maxY = max(b.maxY, y)
                b.score += probMap[y][x]
                b.count++
            }
        }

        // Filter and scale back to original image coordinates
        return components.values.mapNotNull { b ->
            val w = b.maxX - b.minX
            val h = b.maxY - b.minY
            if (w < minBoxSize || h < minBoxSize) return@mapNotNull null
            val avgScore = b.score / b.count
            if (avgScore < boxThreshold) return@mapNotNull null

            // Expand slightly (unclip)
            val expandX = (w * 0.1f).toInt().coerceAtLeast(2)
            val expandY = (h * 0.1f).toInt().coerceAtLeast(2)

            DetBox(
                minX = ((b.minX - expandX) * scaleX).roundToInt().coerceAtLeast(0),
                minY = ((b.minY - expandY) * scaleY).roundToInt().coerceAtLeast(0),
                maxX = ((b.maxX + expandX) * scaleX).roundToInt().coerceAtMost(origW),
                maxY = ((b.maxY + expandY) * scaleY).roundToInt().coerceAtMost(origH),
            )
        }
    }

    // --- Recognition ---

    private fun cropTextRegion(image: BufferedImage, box: DetBox): BufferedImage? {
        val x = box.minX.coerceIn(0, image.width - 1)
        val y = box.minY.coerceIn(0, image.height - 1)
        val w = (box.maxX - x).coerceIn(1, image.width - x)
        val h = (box.maxY - y).coerceIn(1, image.height - y)

        if (w < 3 || h < 3) return null

        var crop = image.getSubimage(x, y, w, h)

        // If the text is vertical (taller than wide), rotate 90° CW for recognition
        if (h > w * 1.5) {
            crop = rotateImage90CW(crop)
        }

        return crop
    }

    private fun runRecognition(crop: BufferedImage): Pair<String, Float> {
        // Resize to fixed height, maintaining aspect ratio, pad to recImgW
        val ratio = recImgH.toFloat() / crop.height
        val targetW = min((crop.width * ratio).roundToInt(), recImgW)
        val resized = resizeImage(crop, targetW, recImgH)

        // Prepare input tensor: [1, 3, recImgH, recImgW]
        val inputData = FloatArray(3 * recImgH * recImgW)
        // Fill with padding value (normalized zero = -mean/std)
        for (c in 0 until 3) {
            val padVal = (0f - mean[c]) / std[c]
            val offset = c * recImgH * recImgW
            for (i in 0 until recImgH * recImgW) {
                inputData[offset + i] = padVal
            }
        }

        // Fill actual image pixels
        for (y in 0 until recImgH) {
            for (x in 0 until targetW) {
                val pixel = resized.getRGB(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                inputData[0 * recImgH * recImgW + y * recImgW + x] = (r - mean[0]) / std[0]
                inputData[1 * recImgH * recImgW + y * recImgW + x] = (g - mean[1]) / std[1]
                inputData[2 * recImgH * recImgW + y * recImgW + x] = (b - mean[2]) / std[2]
            }
        }

        val shape = longArrayOf(1, 3, recImgH.toLong(), recImgW.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)

        val inputName = recSession.inputNames.first()
        val results = recSession.run(mapOf(inputName to tensor))
        val outputTensor = results[0].value

        // CTC decode: output is [1, seq_len, num_classes]
        @Suppress("UNCHECKED_CAST")
        val output = when (outputTensor) {
            is Array<*> -> (outputTensor as Array<Array<FloatArray>>)[0]
            else -> {
                tensor.close()
                results.close()
                return "" to 0f
            }
        }

        tensor.close()
        results.close()

        return ctcDecode(output)
    }

    private fun ctcDecode(output: Array<FloatArray>): Pair<String, Float> {
        val sb = StringBuilder()
        var lastIdx = -1
        var totalConf = 0f
        var charCount = 0

        for (step in output) {
            // Argmax
            var maxIdx = 0
            var maxVal = step[0]
            for (i in 1 until step.size) {
                if (step[i] > maxVal) {
                    maxVal = step[i]
                    maxIdx = i
                }
            }

            // Skip blank (index 0) and consecutive duplicates
            if (maxIdx != 0 && maxIdx != lastIdx) {
                // Dictionary index: maxIdx - 1 (0 is blank)
                val dictIdx = maxIdx - 1
                if (dictIdx in dictionary.indices) {
                    sb.append(dictionary[dictIdx])
                    // Softmax for confidence estimate
                    val expMax = kotlin.math.exp(maxVal.toDouble()).toFloat()
                    val expSum = step.sumOf { kotlin.math.exp(it.toDouble()) }.toFloat()
                    totalConf += expMax / expSum
                    charCount++
                }
            }
            lastIdx = maxIdx
        }

        val avgConf = if (charCount > 0) totalConf / charCount else 0f
        return sb.toString() to avgConf
    }

    override fun close() {
        detSession.close()
        recSession.close()
    }

    // --- Image utilities ---

    private fun imageToNormalizedCHW(image: BufferedImage): FloatArray {
        val w = image.width
        val h = image.height
        val data = FloatArray(3 * h * w)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = image.getRGB(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                data[0 * h * w + y * w + x] = (r - mean[0]) / std[0]
                data[1 * h * w + y * w + x] = (g - mean[1]) / std[1]
                data[2 * h * w + y * w + x] = (b - mean[2]) / std[2]
            }
        }
        return data
    }

    companion object {
        fun rgbBytesToImage(rgb: ByteArray, width: Int, height: Int): BufferedImage {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            var offset = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = rgb[offset++].toInt() and 0xFF
                    val g = rgb[offset++].toInt() and 0xFF
                    val b = rgb[offset++].toInt() and 0xFF
                    image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            return image
        }

        fun resizeImage(image: BufferedImage, width: Int, height: Int): BufferedImage {
            val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(image, 0, 0, width, height, null)
            g.dispose()
            return scaled
        }

        fun rotateImage90CW(image: BufferedImage): BufferedImage {
            val w = image.width
            val h = image.height
            val rotated = BufferedImage(h, w, BufferedImage.TYPE_INT_RGB)
            val g = rotated.createGraphics()
            g.translate(h, 0)
            g.rotate(Math.PI / 2)
            g.drawImage(image, 0, 0, null)
            g.dispose()
            return rotated
        }

        fun roundTo32(n: Int): Int {
            val r = (ceil(n / 32.0) * 32).toInt()
            return if (r < 32) 32 else r
        }
    }
}
