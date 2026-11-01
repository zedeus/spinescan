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
        "eval" -> evalCommand(args.drop(1))
        "predict" -> predictCommand(args.drop(1))
        "experiment" -> experimentCommand(args.drop(1))
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
          harness eval [options]           Run eval on labelled test set
          harness help                     Show this help

        Run options:
          --pack <path>       Path to pack.db (default: pack.db in project root)
          --max-dim <N>       Downscale frames so neither side exceeds N pixels
          --output <dir>      Write annotated images to this directory
          --no-annotate       Skip writing annotated output images
          --models <dir>      Path to OCR models directory (default: models/)

        Eval options:
          --accept            Save the current run as the new baseline
          --testset <dir>     Path to testset directory (default: testset/)
          --models <dir>      Path to OCR models directory (default: models/)

        Examples:
          harness run testset/photos/shelf01.jpg
          harness run testset/photos/ --output eval-output/
          harness eval
          harness eval --accept
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
                TextMatcher.matchAll(spineBox.text, pack.index)
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

private fun evalCommand(args: List<String>) {
    var accept = false
    var testsetPath = findTestset()
    var modelsDir: String? = null
    var forcedPackPath: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--accept" -> accept = true
            "--pack" -> { i++; if (i >= args.size) { System.err.println("--pack requires a value"); return }; forcedPackPath = args[i] }
            "--testset" -> { i++; if (i >= args.size) { System.err.println("--testset requires a value"); return }; testsetPath = args[i] }
            "--models" -> { i++; if (i >= args.size) { System.err.println("--models requires a value"); return }; modelsDir = args[i] }
            else -> System.err.println("Unknown option: ${args[i]}")
        }
        i++
    }

    val testsetDir = File(testsetPath)
    if (!testsetDir.exists()) {
        System.err.println("Testset directory not found: $testsetPath")
        return
    }

    // Load game index — a forced --pack mimics the phone (names-only pack)
    val dbPath = findDb()
    val pack: PackLoader.Pack
    if (forcedPackPath != null) {
        println("Loading pack from $forcedPackPath ...")
        pack = PackLoader.load(forcedPackPath)
        println("  ${pack.games.size} games, ${pack.index.size} name entries, version ${pack.version}")
    } else if (dbPath != null && File(dbPath).exists()) {
        println("Loading full game database from $dbPath ...")
        pack = FullDbLoader.load(dbPath)
        println("  ${pack.games.size} games, ${pack.index.size} name entries")
    } else {
        val packPath = findPack()
        println("Loading pack from $packPath ...")
        pack = PackLoader.load(packPath)
        println("  ${pack.games.size} games, ${pack.index.size} name entries, version ${pack.version}")
    }

    val thresholds = Thresholds.load()
    println("  Thresholds: green=${thresholds.greenScore}, lead=${thresholds.greenLead}, " +
            "frames=${thresholds.greenFrames}, single=${thresholds.greenSingle}, " +
            "floor=${thresholds.orangeFloor}")

    // Init OCR
    println("Initializing OCR engine ...")
    val modelDir = if (modelsDir != null) File(modelsDir) else OcrModels.defaultModelDir()
    OcrModels.ensureModels(modelDir)
    val ocrEngine = PpOcrEngine(modelDir)
    println("  Models loaded from ${modelDir.absolutePath}")
    println()

    val eval = Eval(
        testsetDir = testsetDir,
        pack = pack,
        thresholds = thresholds,
        ocrEngine = ocrEngine,
        outputDir = File(testsetDir, "eval-output"),
    )

    val ok = eval.run(accept = accept)
    if (!ok) {
        System.exit(1)
    }
}

/**
 * Run pipeline on all test photos and output predictions as JSON for the labeling page.
 * Writes testset/predictions.json with box detections per photo.
 */
private fun predictCommand(args: List<String>) {
    var testsetPath = findTestset()
    var modelsDir: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--testset" -> { i++; testsetPath = args[i] }
            "--models" -> { i++; modelsDir = args[i] }
            else -> System.err.println("Unknown option: ${args[i]}")
        }
        i++
    }

    val testsetDir = File(testsetPath)
    val photosDir = File(testsetDir, "photos")

    // Load game index
    val dbPath = findDb()
    val packPath = findPack()
    val pack: PackLoader.Pack
    if (dbPath != null && File(dbPath).exists()) {
        println("Loading full game database from $dbPath ...")
        pack = FullDbLoader.load(dbPath)
        println("  ${pack.games.size} games, ${pack.index.size} name entries")
    } else {
        println("Loading pack from $packPath ...")
        pack = PackLoader.load(packPath)
        println("  ${pack.games.size} games, ${pack.index.size} name entries")
    }

    val thresholds = Thresholds.load()

    println("Initializing OCR engine ...")
    val modelDir = if (modelsDir != null) File(modelsDir) else OcrModels.defaultModelDir()
    OcrModels.ensureModels(modelDir)
    val ocrEngine = PpOcrEngine(modelDir)
    println()

    // Find all photos recursively
    val photos = mutableListOf<File>()
    fun collectPhotos(dir: File) {
        dir.listFiles()?.sorted()?.forEach { f ->
            if (f.isDirectory) collectPhotos(f)
            else if (FrameSource.isImage(f)) photos.add(f)
        }
    }
    collectPhotos(photosDir)
    println("Found ${photos.size} photos")

    val predictions = mutableListOf<Map<String, Any?>>()
    val grouper = BoxGrouper()

    for (photo in photos) {
        val relPath = photo.relativeTo(photosDir).path
        print("  $relPath ... ")

        val frameSource = FrameSource()
        val frames = frameSource.load(photo.path, maxDimension = 0)
        if (frames.isEmpty()) { println("skip"); continue }
        val frame = frames[0]

        val textLines = ocrEngine.detectFromImage(frame.image)
        val spineBoxes = grouper.group(textLines)

        val boxes = spineBoxes.map { spineBox ->
            val normText = Normalize.normalize(spineBox.text)
            val candidates = if (normText.isNotBlank()) {
                TextMatcher.matchAll(spineBox.text, pack.index, topN = 3)
            } else emptyList()

            val state = BoxState(0, thresholds)
            state.addRead(normText, candidates)

            mapOf(
                "text" to spineBox.text,
                "rect" to listOf(spineBox.box.x, spineBox.box.y, spineBox.box.width, spineBox.box.height),
                "colour" to state.colour.name.lowercase(),
                "vertical" to spineBox.isVertical,
                "match" to state.bestCandidate?.let { c ->
                    mapOf("name" to c.name, "score" to c.score, "bggIds" to c.bggIds)
                },
                "candidates" to candidates.map { c ->
                    mapOf("name" to c.name, "score" to c.score, "bggIds" to c.bggIds)
                },
            )
        }

        predictions.add(mapOf(
            "photo" to relPath,
            "width" to frame.image.width,
            "height" to frame.image.height,
            "boxes" to boxes,
        ))

        val g = boxes.count { (it["colour"] as String) == "green" }
        val o = boxes.count { (it["colour"] as String) == "orange" }
        val r = boxes.count { (it["colour"] as String) == "red" }
        println("${textLines.size} lines → ${boxes.size} boxes (G:$g O:$o R:$r)")
    }

    // Write JSON
    val outFile = File(testsetDir, "predictions.json")
    val jsonStr = kotlinx.serialization.json.Json { prettyPrint = false }
        .encodeToString(kotlinx.serialization.json.JsonElement.serializer(),
            toJsonElement(predictions))
    outFile.writeText(jsonStr)
    println("\nPredictions saved to ${outFile.path}")
}

@Suppress("UNCHECKED_CAST")
private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
    return when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> kotlinx.serialization.json.JsonObject(
            (value as Map<String, Any?>).mapValues { toJsonElement(it.value) }
        )
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }
}

/**
 * Run OCR preprocessing experiments on test photos.
 * Tries every preprocessing strategy on each box crop and reports which ones
 * find the most correct matches.
 */
private fun experimentCommand(args: List<String>) {
    var testsetPath = findTestset()
    var modelsDir: String? = null
    var photoFilter: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--testset" -> { i++; testsetPath = args[i] }
            "--models" -> { i++; modelsDir = args[i] }
            "--photo" -> { i++; photoFilter = args[i] }
            else -> System.err.println("Unknown option: ${args[i]}")
        }
        i++
    }

    val testsetDir = File(testsetPath)
    val photosDir = File(testsetDir, "photos")

    // Load game index
    val dbPath = findDb()
    val packPath = findPack()
    val pack: PackLoader.Pack
    if (dbPath != null && File(dbPath).exists()) {
        pack = FullDbLoader.load(dbPath)
        println("Loaded ${pack.games.size} games")
    } else {
        pack = PackLoader.load(packPath)
        println("Loaded pack: ${pack.games.size} games")
    }

    val thresholds = Thresholds.load()
    val modelDir = if (modelsDir != null) File(modelsDir) else OcrModels.defaultModelDir()
    OcrModels.ensureModels(modelDir)
    val ocrEngine = PpOcrEngine(modelDir)
    val experiment = OcrExperiment(ocrEngine, pack.index)

    // Load labels
    val labelsDir = File(testsetDir, "labels")
    val allLabels = Labels.loadAll(labelsDir).associateBy { it.photo }

    // Find photos
    val photos = mutableListOf<File>()
    fun collectPhotos(dir: File) {
        dir.listFiles()?.sorted()?.forEach { f ->
            if (f.isDirectory) collectPhotos(f)
            else if (FrameSource.isImage(f)) {
                if (photoFilter == null || f.path.contains(photoFilter!!)) {
                    photos.add(f)
                }
            }
        }
    }
    collectPhotos(photosDir)

    // Strategy win counts
    val strategyWins = mutableMapOf<String, Int>()
    val strategyBetter = mutableMapOf<String, Int>()
    var totalBoxes = 0

    for (photo in photos) {
        val relPath = photo.relativeTo(photosDir).path
        val label = allLabels[relPath] ?: continue
        val labelNames = Labels.normalisedNames(label)

        println("\n$relPath (${label.games.size} labelled):")

        val frameSource = FrameSource()
        val frames = frameSource.load(photo.path, maxDimension = 0)
        if (frames.isEmpty()) continue
        val frame = frames[0]

        // Initial OCR
        val textLines = ocrEngine.detectFromImage(frame.image)
        val grouper = BoxGrouper()
        val spineBoxes = grouper.group(textLines)

        for (spineBox in spineBoxes) {
            totalBoxes++

            // Crop the box with padding
            val rect = spineBox.box
            val pad = maxOf(rect.width, rect.height) / 2
            val cx = maxOf(0, rect.x - pad)
            val cy = maxOf(0, rect.y - pad)
            val cw = minOf(frame.image.width - cx, rect.width + pad * 2)
            val ch = minOf(frame.image.height - cy, rect.height + pad * 2)
            if (cw < 20 || ch < 20) continue

            val crop = frame.image.getSubimage(cx, cy, cw, ch)

            // Try all strategies
            val results = experiment.tryAllStrategies(crop)

            // Find which strategies match a labelled game
            val rawResult = results.firstOrNull { it.strategy == "raw" }
            val rawMatch = rawResult?.matchName?.let { Normalize.normalize(it) }
            val rawCorrect = rawMatch != null && labelNames.any {
                it.startsWith(rawMatch) || rawMatch.startsWith(it)
            }

            for (r in results) {
                if (r.matchName == null) continue
                val norm = Normalize.normalize(r.matchName)
                val correct = labelNames.any {
                    it.startsWith(norm) || norm.startsWith(it)
                }
                if (correct) {
                    strategyWins[r.strategy] = (strategyWins[r.strategy] ?: 0) + 1
                    if (!rawCorrect) {
                        strategyBetter[r.strategy] = (strategyBetter[r.strategy] ?: 0) + 1
                    }
                }
            }

            // Print interesting cases: strategies that found correct matches raw missed
            val rawStr = if (rawResult != null) "\"${rawResult.text.take(25)}\" → ${rawResult.matchName ?: "?"}" else "no read"
            val winners = results.filter { r ->
                r.matchName != null && Normalize.normalize(r.matchName).let { norm ->
                    labelNames.any { it.startsWith(norm) || norm.startsWith(it) }
                }
            }.map { it.strategy }.distinct()

            if (winners.isNotEmpty() && !rawCorrect) {
                println("  ✨ ${winners.joinToString(",")} found match; raw: $rawStr")
                val best = results.filter { it.strategy in winners }.maxByOrNull { it.matchScore }
                if (best != null) println("     → ${best.matchName} (${best.strategy}, ${String.format("%.2f", best.matchScore)})")
            }
        }
    }

    println("\n${"=".repeat(60)}")
    println("STRATEGY RESULTS ($totalBoxes boxes)")
    println("=".repeat(60))
    println("\nCorrect matches by strategy:")
    for ((strat, wins) in strategyWins.entries.sortedByDescending { it.value }) {
        val better = strategyBetter[strat] ?: 0
        println("  ${strat.padEnd(20)} $wins correct ($better rescued from raw miss)")
    }
}

private fun findTestset(): String {
    for (c in listOf("testset", "../testset")) {
        if (File(c).exists()) return c
    }
    return "testset"
}
