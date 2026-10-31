package spinescan.core

/**
 * A single text line detected by OCR.
 *
 * @property box bounding rectangle in image coordinates
 * @property text recognised text content
 * @property confidence recognition confidence, 0.0 to 1.0
 * @property isVertical true if the text runs vertically (taller than wide)
 */
data class TextLine(
    val box: Rect,
    val text: String,
    val confidence: Float,
    val isVertical: Boolean = false,
)

/**
 * Interface for text recognition engines.
 *
 * Implementations: PP-OCR via ONNX Runtime (harness + phone),
 * ML Kit Text Recognition v2 (phone only).
 *
 * The interface is free of ONNX, ML Kit or any platform types.
 */
fun interface OcrEngine {
    /**
     * Detect and recognise text in an image.
     *
     * @param imageData RGB pixel data, row-major, 3 bytes per pixel
     * @param width image width in pixels
     * @param height image height in pixels
     * @return list of detected text lines
     */
    fun detect(imageData: ByteArray, width: Int, height: Int): List<TextLine>
}
