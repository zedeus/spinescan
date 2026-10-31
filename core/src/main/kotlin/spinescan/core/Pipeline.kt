package spinescan.core

/**
 * Orchestrates the per-frame scanning pipeline:
 * OCR → group text lines → match against pack → update box colours.
 *
 * Stateful: tracks BoxState across frames for multi-frame agreement.
 */
class Pipeline(
    private val ocrEngine: OcrEngine,
    private val pack: PackLoader.Pack,
    private val thresholds: Thresholds,
    private val aliasProvider: AliasProvider = AliasProvider.EMPTY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val grouper = BoxGrouper()
    private val boxStates = mutableMapOf<Int, BoxState>()
    private var nextBoxId = 0

    /**
     * Result of processing one frame.
     */
    data class FrameResult(
        val boxes: List<DetectedBox>,
        val textLines: List<TextLine>,
        val frameIndex: Int,
    )

    /**
     * A detected box with its colour decision and match info.
     */
    data class DetectedBox(
        val boxId: Int,
        val rect: Rect,
        val text: String,
        val colour: BoxColour,
        val matchName: String?,
        val matchScore: Double,
        val matchBggIds: List<Int>,
        val isVertical: Boolean,
        val readCount: Int,
    )

    /**
     * Process a single frame.
     *
     * In photo mode (no tracking), each frame creates fresh box IDs.
     * In video mode (#6), tracking carries box IDs across frames.
     */
    fun processFrame(
        imageData: ByteArray,
        width: Int,
        height: Int,
        frameIndex: Int = 0,
    ): FrameResult {
        // 1. OCR
        val textLines = ocrEngine.detect(imageData, width, height)

        // 2. Group into spine boxes
        val spineBoxes = grouper.group(textLines)

        // 3. Match each box and update state
        val detected = spineBoxes.map { spineBox ->
            val boxId = nextBoxId++
            val state = BoxState(boxId, thresholds, aliasProvider, clock)
            boxStates[boxId] = state

            // Normalise and look up
            val normText = Normalize.normalize(spineBox.text)
            val candidates = if (normText.isNotBlank()) {
                pack.index.lookup(normText)
            } else {
                emptyList()
            }

            state.addRead(normText, candidates)

            DetectedBox(
                boxId = boxId,
                rect = spineBox.box,
                text = spineBox.text,
                colour = state.colour,
                matchName = state.bestCandidate?.name,
                matchScore = state.bestCandidate?.score ?: 0.0,
                matchBggIds = state.bestCandidate?.bggIds ?: emptyList(),
                isVertical = spineBox.isVertical,
                readCount = state.readCount,
            )
        }

        return FrameResult(boxes = detected, textLines = textLines, frameIndex = frameIndex)
    }

    /** Reset all state for a new scan. */
    fun reset() {
        boxStates.clear()
        nextBoxId = 0
    }

    /** Current box states, keyed by box ID. */
    val states: Map<Int, BoxState> get() = boxStates
}
