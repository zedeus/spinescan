package spinescan.harness

import spinescan.core.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run" -> runCommand(args.drop(1))
        "help", "--help", "-h" -> printUsage()
        else -> {
            if (File(args[0]).exists()) {
                runCommand(args.toList())
            } else {
                System.err.println("Unknown command: ${args[0]}")
                printUsage()
            }
        }
    }
}

private fun printUsage() {
    println("""
        spinescan harness — replay tool for testing the scanning pipeline

        Usage:
          harness run <path> [options]     Run pipeline on a photo, directory, or video
          harness help                     Show this help

        Options:
          --pack <path>       Path to pack.db (default: pack.db in project root)
          --max-dim <N>       Downscale frames so neither side exceeds N pixels
          --output <dir>      Write annotated images to this directory
          --no-annotate       Skip writing annotated output images
          --models <dir>      Path to OCR models directory (default: models/)

        Examples:
          harness run testset/photos/shelf01.jpg
          harness run testset/photos/ --output eval-output/
          harness run testset/video.mp4 --max-dim 1920
    """.trimIndent())
}

private fun runCommand(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Error: path required. Usage: harness run <path>")
        return
    }

    val path = args[0]
    var packPath = findPack()
    var dbPath: String? = findDb()
    var maxDim = 0
    var outputDir: String? = null
    var annotate = true
    var modelsDir: String? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--pack" -> { i++; if (i >= args.size) { System.err.println("--pack requires a value"); return }; packPath = args[i]; dbPath = null }
            "--db" -> { i++; if (i >= args.size) { System.err.println("--db requires a value"); return }; dbPath = args[i] }
            "--max-dim" -> { i++; if (i >= args.size) { System.err.println("--max-dim requires a value"); return }; maxDim = args[i].toInt() }
            "--output" -> { i++; if (i >= args.size) { System.err.println("--output requires a value"); return }; outputDir = args[i] }
            "--no-annotate" -> annotate = false
            "--models" -> { i++; if (i >= args.size) { System.err.println("--models requires a value"); return }; modelsDir = args[i] }
            else -> System.err.println("Unknown option: ${args[i]}")
        }
        i++
    }

    // Load game index — prefer full games.db over the 10k pack
    val pack: PackLoader.Pack
    if (dbPath != null && File(dbPath).exists()) {
        println("Loading full game database from $dbPath ...")
        pack = FullDbLoader.load(dbPath!!)
        println("  ${pack.games.size} games, ${pack.index.size} name entries")
    } else {
        println("Loading pack from $packPath ...")
        pack = PackLoader.load(packPath)
        println("  ${pack.games.size} games, ${pack.index.size} name entries, version ${pack.version}")
    }

    // Load thresholds
    val thresholds = Thresholds.load()
    println("  Thresholds: green=${thresholds.greenScore}, lead=${thresholds.greenLead}, " +
            "frames=${thresholds.greenFrames}, single=${thresholds.greenSingle}, " +
            "floor=${thresholds.orangeFloor}")

    // Init OCR
    println("Initializing OCR engine (PP-OCRv4 via ONNX Runtime) ...")
    val modelDir = if (modelsDir != null) File(modelsDir) else OcrModels.defaultModelDir()
    OcrModels.ensureModels(modelDir)
    val ocrEngine = PpOcrEngine(modelDir)
    println("  Models loaded from ${modelDir.absolutePath}")

    // Load frames
    println("Loading frames from $path ...")
    val frameSource = FrameSource()
    val frames = frameSource.load(path, maxDimension = maxDim)
    println("  ${frames.size} frame(s) loaded")

    if (frames.isEmpty()) {
        println("No frames found.")
        return
    }

    // Set up output
    val outDir = if (annotate) {
        val dir = File(outputDir ?: "eval-output")
        dir.mkdirs()
        dir
    } else null

    // Process each frame
    var totalBoxes = 0
    var greenCount = 0
    var orangeCount = 0
    var redCount = 0

    for (frame in frames) {
        val sourceName = File(frame.sourcePath).nameWithoutExtension
        print("  Frame ${frame.index}: $sourceName ... ")

        val t0 = System.nanoTime()

        // Run OCR directly on the image for efficiency
        val textLines = ocrEngine.detectFromImage(frame.image)

        // Group into spine boxes
        val grouper = BoxGrouper()
        val spineBoxes = grouper.group(textLines)

        // Match and colour each box
        val boxes = spineBoxes.map { spineBox ->
            val normText = Normalize.normalize(spineBox.text)
            val candidates = if (normText.isNotBlank()) {
                pack.index.lookup(normText)
            } else emptyList()

            val state = BoxState(0, thresholds)
            state.addRead(normText, candidates)

            Pipeline.DetectedBox(
                boxId = 0,
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

        val result = Pipeline.FrameResult(boxes = boxes, textLines = textLines, frameIndex = frame.index)
        val elapsed = (System.nanoTime() - t0) / 1_000_000

        totalBoxes += result.boxes.size
        for (box in result.boxes) {
            when (box.colour) {
                BoxColour.GREEN -> greenCount++
                BoxColour.ORANGE -> orangeCount++
                BoxColour.RED -> redCount++
                BoxColour.WHITE -> {}
            }
        }

        println("${result.textLines.size} lines → ${result.boxes.size} boxes " +
                "(G:${result.boxes.count { it.colour == BoxColour.GREEN }} " +
                "O:${result.boxes.count { it.colour == BoxColour.ORANGE }} " +
                "R:${result.boxes.count { it.colour == BoxColour.RED }}) " +
                "[${elapsed}ms]")

        // Print box details
        for (box in result.boxes) {
            val icon = when (box.colour) {
                BoxColour.GREEN -> "🟢"
                BoxColour.ORANGE -> "🟠"
                BoxColour.RED -> "🔴"
                BoxColour.WHITE -> "⚪"
            }
            val matchInfo = if (box.matchName != null) {
                " → ${box.matchName} (${String.format("%.3f", box.matchScore)}) [${box.matchBggIds.joinToString(",")}]"
            } else ""
            println("    $icon \"${box.text}\"$matchInfo")
        }

        // Write annotated image
        if (outDir != null) {
            val annotated = annotateFrame(frame.image, result)
            val outFile = File(outDir, "${sourceName}_annotated.jpg")
            ImageIO.write(annotated, "jpg", outFile)
        }
    }

    // Summary
    println()
    println("=".repeat(60))
    println("Summary: ${frames.size} frame(s), $totalBoxes box(es)")
    println("  🟢 Green:  $greenCount")
    println("  🟠 Orange: $orangeCount")
    println("  🔴 Red:    $redCount")
    println("=".repeat(60))
}

private fun annotateFrame(image: BufferedImage, result: Pipeline.FrameResult): BufferedImage {
    val annotated = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    val g = annotated.createGraphics()
    g.drawImage(image, 0, 0, null)
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING,
        java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
    )

    // Draw text line boxes (thin, gray)
    g.color = Color(128, 128, 128, 100)
    g.stroke = BasicStroke(1f)
    for (line in result.textLines) {
        g.drawRect(line.box.x, line.box.y, line.box.width, line.box.height)
    }

    // Draw detected spine boxes (thick, coloured)
    g.stroke = BasicStroke(3f)
    val font = Font("SansSerif", Font.BOLD, 14)
    g.font = font

    for (box in result.boxes) {
        val colour = when (box.colour) {
            BoxColour.GREEN -> Color(0, 200, 0, 200)
            BoxColour.ORANGE -> Color(255, 165, 0, 200)
            BoxColour.RED -> Color(220, 0, 0, 200)
            BoxColour.WHITE -> Color(200, 200, 200, 200)
        }

        g.color = colour
        g.drawRect(box.rect.x, box.rect.y, box.rect.width, box.rect.height)

        // Label
        val label = if (box.matchName != null) {
            "${box.matchName} (${String.format("%.2f", box.matchScore)})"
        } else {
            box.text.take(30)
        }

        val fm = g.fontMetrics
        val labelW = fm.stringWidth(label) + 6
        val labelH = fm.height + 4
        val labelX = box.rect.x
        val labelY = maxOf(0, box.rect.y - labelH)

        g.color = Color(0, 0, 0, 160)
        g.fillRect(labelX, labelY, labelW, labelH)
        g.color = colour
        g.drawString(label, labelX + 3, labelY + fm.ascent + 2)
    }

    g.dispose()
    return annotated
}

private fun findPack(): String {
    for (c in listOf("pack.db", "../pack.db", "data/pack.db", "../data/pack.db")) {
        if (File(c).exists()) return c
    }
    return "pack.db"
}

private fun findDb(): String? {
    for (c in listOf("games.db", "../games.db", "data/games.db", "../data/games.db")) {
        if (File(c).exists()) return c
    }
    return null
}
