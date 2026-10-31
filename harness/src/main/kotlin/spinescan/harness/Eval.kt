package spinescan.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import spinescan.core.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Eval runner: processes every labelled photo through the pipeline,
 * compares results against ground truth, produces metrics and a report.
 */

@Serializable
data class PhotoMetrics(
    val photo: String,
    val set: String,
    val draft: Boolean,
    val legibleCount: Int,
    val greenFound: Int,
    val wrongGreens: Int,
    val oranges: Int,
    val reds: Int,
    val totalBoxes: Int,
    val cloudCalls: Int = 0,
    val elapsedMs: Long = 0,
    /** Names of correct green matches. */
    val correctGreenNames: List<String> = emptyList(),
    /** Names of wrong green matches (for debugging). */
    val wrongGreenNames: List<String> = emptyList(),
    /** Legible games that were not found green. */
    val missedNames: List<String> = emptyList(),
)

@Serializable
data class AggregateMetrics(
    val set: String,
    val photoCount: Int,
    val legibleCount: Int,
    val greenFound: Int,
    val wrongGreens: Int,
    val oranges: Int,
    val reds: Int,
    val totalBoxes: Int,
    val cloudCalls: Int,
    val greenRate: Double,
    val wrongGreenRate: Double,
)

@Serializable
data class EvalRun(
    val timestamp: String,
    val photos: List<PhotoMetrics>,
    val tune: AggregateMetrics,
    val holdout: AggregateMetrics,
    val overall: AggregateMetrics,
)

@Serializable
data class Baseline(
    val timestamp: String,
    val tune: AggregateMetrics,
    val holdout: AggregateMetrics,
    val overall: AggregateMetrics,
)

class Eval(
    private val testsetDir: File,
    private val pack: PackLoader.Pack,
    private val thresholds: Thresholds,
    private val ocrEngine: PpOcrEngine,
    private val outputDir: File = File(testsetDir, "eval-output"),
    private val baselinesDir: File = File(testsetDir, "baselines"),
) {
    private val labelsDir = File(testsetDir, "labels")
    private val photosDir = File(testsetDir, "photos")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun run(accept: Boolean = false): Boolean {
        val labels = Labels.loadAll(labelsDir)
        if (labels.isEmpty()) {
            System.err.println("No label files found in ${labelsDir.path}")
            return false
        }

        println("Eval: ${labels.size} labelled photos")
        println("  Tune: ${labels.count { it.set == "tune" }}, Holdout: ${labels.count { it.set == "holdout" }}")
        val draftCount = labels.count { it.draft }
        if (draftCount > 0) {
            println("  ⚠ $draftCount label(s) are drafts (not yet approved)")
        }
        println()

        outputDir.mkdirs()

        val photoMetrics = mutableListOf<PhotoMetrics>()

        for (label in labels) {
            val photoFile = File(photosDir, label.photo)
            if (!photoFile.exists()) {
                System.err.println("  ⚠ Photo not found: ${label.photo}, skipping")
                continue
            }

            print("  ${label.photo} ... ")

            val t0 = System.nanoTime()
            val metrics = evaluatePhoto(label, photoFile)
            val elapsed = (System.nanoTime() - t0) / 1_000_000

            val m = metrics.copy(elapsedMs = elapsed)
            photoMetrics.add(m)

            val icon = if (m.wrongGreens > 0) "❌" else if (m.greenFound > 0) "✅" else "⬜"
            println("$icon G:${m.greenFound}/${m.legibleCount} WG:${m.wrongGreens} O:${m.oranges} R:${m.reds} [${elapsed}ms]")

            if (m.wrongGreens > 0) {
                for (name in m.wrongGreenNames) {
                    println("    ❌ wrong green: $name")
                }
            }
        }

        // Aggregate
        val tune = aggregate("tune", photoMetrics.filter { it.set == "tune" })
        val holdout = aggregate("holdout", photoMetrics.filter { it.set == "holdout" })
        val overall = aggregate("overall", photoMetrics)

        val run = EvalRun(
            timestamp = java.time.Instant.now().toString(),
            photos = photoMetrics,
            tune = tune,
            holdout = holdout,
            overall = overall,
        )

        // Save run results
        val runFile = File(outputDir, "eval-run.json")
        runFile.writeText(json.encodeToString(EvalRun.serializer(), run))

        // Print report
        println()
        printReport(run)

        // Compare with baseline
        val baselineFile = File(baselinesDir, "baseline.json")
        val baseline = if (baselineFile.exists()) {
            json.decodeFromString<Baseline>(baselineFile.readText())
        } else null

        val regressions = if (baseline != null) {
            println()
            compareBaseline(baseline, run)
        } else {
            println()
            println("No baseline found. Run with --accept to save the current run as baseline.")
            emptyList()
        }

        // Generate HTML report
        generateHtmlReport(run, baseline, regressions)

        // Accept as baseline
        if (accept) {
            baselinesDir.mkdirs()
            val newBaseline = Baseline(
                timestamp = run.timestamp,
                tune = run.tune,
                holdout = run.holdout,
                overall = run.overall,
            )
            baselineFile.writeText(json.encodeToString(Baseline.serializer(), newBaseline))
            println()
            println("✅ Baseline saved to ${baselineFile.path}")
        }

        return regressions.isEmpty()
    }

    private fun evaluatePhoto(label: LabelFile, photoFile: File): PhotoMetrics {
        val frameSource = FrameSource()
        val frames = frameSource.load(photoFile.path, maxDimension = 0)
        if (frames.isEmpty()) return emptyMetrics(label)

        val frame = frames[0]

        // Run pipeline
        val grouper = BoxGrouper()
        val textLines = ocrEngine.detectFromImage(frame.image)
        val spineBoxes = grouper.group(textLines)

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

        val result = Pipeline.FrameResult(boxes = boxes, textLines = textLines, frameIndex = 0)

        // Write annotated image
        val annotated = annotateFrame(frame.image, result)
        val outName = photoFile.nameWithoutExtension + "_annotated.jpg"
        // Preserve subdirectory structure
        val subDir = File(label.photo).parent
        val annDir = if (subDir != null) File(outputDir, subDir).also { it.mkdirs() } else outputDir
        ImageIO.write(annotated, "jpg", File(annDir, outName))

        // Match against labels
        val allLabelled = Labels.normalisedNames(label)
        val legibleLabelled = Labels.legibleNames(label)

        val greenBoxes = boxes.filter { it.colour == BoxColour.GREEN }
        val correctGreens = mutableListOf<String>()
        val wrongGreens = mutableListOf<String>()

        for (box in greenBoxes) {
            val name = box.matchName
            val matchNorm = if (name != null) Normalize.normalize(name) else ""
            if (matchesAnyLabel(matchNorm, allLabelled)) {
                correctGreens.add(name ?: "")
            } else {
                wrongGreens.add("${name ?: box.text} (score=${String.format("%.3f", box.matchScore)})")
            }
        }

        // Missed: legible games that weren't found green
        val foundNorm = greenBoxes
            .mapNotNull { it.matchName }
            .map { Normalize.normalize(it) }
            .toSet()
        val missed = label.games
            .filter { it.legible }
            .filter { g ->
                val gNorm = Normalize.normalize(g.name)
                !foundNorm.any { f -> matchesAnyLabel(f, setOf(gNorm)) }
            }
            .map { it.name }

        return PhotoMetrics(
            photo = label.photo,
            set = label.set,
            draft = label.draft,
            legibleCount = label.games.count { it.legible },
            greenFound = correctGreens.size,
            wrongGreens = wrongGreens.size,
            oranges = boxes.count { it.colour == BoxColour.ORANGE },
            reds = boxes.count { it.colour == BoxColour.RED },
            totalBoxes = boxes.size,
            correctGreenNames = correctGreens,
            wrongGreenNames = wrongGreens,
            missedNames = missed,
        )
    }

    /**
     * Check if a match name corresponds to any labelled game.
     * Handles name variants: "Deception" matches "Deception: Murder in Hong Kong",
     * "Brass" matches "Brass: Birmingham", etc.
     * Uses prefix matching: if either name starts with the other (after normalisation),
     * it counts as a match, as long as the shorter one is at least 5 chars.
     */
    private fun matchesAnyLabel(matchNorm: String, labels: Set<String>): Boolean {
        if (matchNorm in labels) return true
        if (matchNorm.length < 5) return false
        return labels.any { label ->
            label.startsWith(matchNorm) || matchNorm.startsWith(label)
        }
    }

    private fun emptyMetrics(label: LabelFile) = PhotoMetrics(
        photo = label.photo,
        set = label.set,
        draft = label.draft,
        legibleCount = label.games.count { it.legible },
        greenFound = 0, wrongGreens = 0, oranges = 0, reds = 0, totalBoxes = 0,
    )

    private fun aggregate(set: String, metrics: List<PhotoMetrics>): AggregateMetrics {
        val legible = metrics.sumOf { it.legibleCount }
        val greenFound = metrics.sumOf { it.greenFound }
        val wrongGreens = metrics.sumOf { it.wrongGreens }
        val totalGreens = greenFound + wrongGreens
        return AggregateMetrics(
            set = set,
            photoCount = metrics.size,
            legibleCount = legible,
            greenFound = greenFound,
            wrongGreens = wrongGreens,
            oranges = metrics.sumOf { it.oranges },
            reds = metrics.sumOf { it.reds },
            totalBoxes = metrics.sumOf { it.totalBoxes },
            cloudCalls = metrics.sumOf { it.cloudCalls },
            greenRate = if (legible > 0) greenFound.toDouble() / legible else 0.0,
            wrongGreenRate = if (totalGreens > 0) wrongGreens.toDouble() / totalGreens else 0.0,
        )
    }

    private fun printReport(run: EvalRun) {
        println("=" .repeat(70))
        println("EVAL REPORT")
        println("=".repeat(70))

        fun printAgg(agg: AggregateMetrics) {
            println("  Photos: ${agg.photoCount}")
            println("  Legible games: ${agg.legibleCount}")
            println("  Green found:   ${agg.greenFound} (${String.format("%.1f%%", agg.greenRate * 100)})")
            println("  Wrong greens:  ${agg.wrongGreens} (${String.format("%.1f%%", agg.wrongGreenRate * 100)})")
            println("  Oranges:       ${agg.oranges}")
            println("  Reds:          ${agg.reds}")
            println("  Total boxes:   ${agg.totalBoxes}")
            if (agg.cloudCalls > 0) println("  Cloud calls:   ${agg.cloudCalls}")
        }

        if (run.tune.photoCount > 0) {
            println()
            println("── Tune ──")
            printAgg(run.tune)
        }
        if (run.holdout.photoCount > 0) {
            println()
            println("── Holdout ──")
            printAgg(run.holdout)
        }
        println()
        println("── Overall ──")
        printAgg(run.overall)
        println("=".repeat(70))

        // Worst failures
        val worst = run.photos
            .filter { it.wrongGreens > 0 || (it.legibleCount > 0 && it.greenFound == 0) }
            .sortedByDescending { it.wrongGreens }
        if (worst.isNotEmpty()) {
            println()
            println("Worst failures:")
            for (m in worst.take(10)) {
                println("  ${m.photo}: WG=${m.wrongGreens} G=${m.greenFound}/${m.legibleCount}")
                for (n in m.wrongGreenNames) println("    ❌ $n")
                if (m.missedNames.size <= 5) {
                    for (n in m.missedNames) println("    ⬜ missed: $n")
                } else {
                    println("    ⬜ missed ${m.missedNames.size} games")
                }
            }
        }
    }

    data class Regression(val metric: String, val baseline: String, val current: String)

    private fun compareBaseline(baseline: Baseline, run: EvalRun): List<Regression> {
        println("Comparing with baseline from ${baseline.timestamp}:")
        val regressions = mutableListOf<Regression>()

        fun check(name: String, baseAgg: AggregateMetrics, runAgg: AggregateMetrics) {
            // Wrong greens must not increase
            if (runAgg.wrongGreens > baseAgg.wrongGreens) {
                val r = Regression("$name wrong_greens", "${baseAgg.wrongGreens}", "${runAgg.wrongGreens}")
                regressions.add(r)
                println("  ❌ $name wrong_greens: ${baseAgg.wrongGreens} → ${runAgg.wrongGreens} (REGRESSION)")
            }
            // Green rate must not decrease
            if (runAgg.greenRate < baseAgg.greenRate - 0.001) {
                val r = Regression("$name green_rate",
                    String.format("%.1f%%", baseAgg.greenRate * 100),
                    String.format("%.1f%%", runAgg.greenRate * 100))
                regressions.add(r)
                println("  ❌ $name green_rate: ${String.format("%.1f%%", baseAgg.greenRate * 100)} → ${String.format("%.1f%%", runAgg.greenRate * 100)} (REGRESSION)")
            }
            // Wrong green rate must not increase
            if (runAgg.wrongGreenRate > baseAgg.wrongGreenRate + 0.001) {
                val r = Regression("$name wrong_green_rate",
                    String.format("%.1f%%", baseAgg.wrongGreenRate * 100),
                    String.format("%.1f%%", runAgg.wrongGreenRate * 100))
                regressions.add(r)
                println("  ❌ $name wrong_green_rate: ${String.format("%.1f%%", baseAgg.wrongGreenRate * 100)} → ${String.format("%.1f%%", runAgg.wrongGreenRate * 100)} (REGRESSION)")
            }

            if (regressions.none { it.metric.startsWith(name) }) {
                val delta = runAgg.greenRate - baseAgg.greenRate
                val symbol = if (delta > 0.001) "📈" else if (delta < -0.001) "📉" else "➡️"
                println("  $symbol $name: green ${String.format("%.1f%%", baseAgg.greenRate * 100)} → ${String.format("%.1f%%", runAgg.greenRate * 100)}, WG ${baseAgg.wrongGreens} → ${runAgg.wrongGreens}")
            }
        }

        check("overall", baseline.overall, run.overall)
        if (baseline.tune.photoCount > 0) check("tune", baseline.tune, run.tune)
        if (baseline.holdout.photoCount > 0) check("holdout", baseline.holdout, run.holdout)

        if (regressions.isEmpty()) {
            println("  ✅ No regressions")
        } else {
            println()
            println("❌ ${regressions.size} regression(s) detected — eval FAILED")
        }
        return regressions
    }

    private fun generateHtmlReport(run: EvalRun, baseline: Baseline?, regressions: List<Regression>) {
        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><head><meta charset='utf-8'><title>Spinescan Eval Report</title>")
            appendLine("<style>")
            appendLine("""
                body { font-family: system-ui, sans-serif; max-width: 1200px; margin: 0 auto; padding: 20px; background: #1a1a2e; color: #e0e0e0; }
                h1 { color: #fff; } h2 { color: #ccc; border-bottom: 1px solid #333; padding-bottom: 4px; }
                table { border-collapse: collapse; width: 100%; margin: 12px 0; }
                th, td { padding: 6px 12px; text-align: left; border: 1px solid #333; }
                th { background: #16213e; }
                .good { color: #4ade80; } .bad { color: #f87171; } .warn { color: #fbbf24; }
                .regression { background: #4c1d1d; }
                .photo-row td { font-size: 0.9em; }
                details { margin: 8px 0; }
                summary { cursor: pointer; font-weight: bold; }
            """.trimIndent())
            appendLine("</style></head><body>")
            appendLine("<h1>🎲 Spinescan Eval Report</h1>")
            appendLine("<p>Run: ${run.timestamp}</p>")

            if (baseline != null) {
                appendLine("<h2>Baseline Comparison</h2>")
                if (regressions.isEmpty()) {
                    appendLine("<p class='good'>✅ No regressions</p>")
                } else {
                    appendLine("<p class='bad'>❌ ${regressions.size} regression(s)</p>")
                    appendLine("<table><tr><th>Metric</th><th>Baseline</th><th>Current</th></tr>")
                    for (r in regressions) {
                        appendLine("<tr class='regression'><td>${r.metric}</td><td>${r.baseline}</td><td>${r.current}</td></tr>")
                    }
                    appendLine("</table>")
                }
            }

            fun aggTable(label: String, agg: AggregateMetrics, base: AggregateMetrics?) {
                appendLine("<h2>$label</h2>")
                appendLine("<table>")
                appendLine("<tr><th>Metric</th><th>Value</th>${if (base != null) "<th>Baseline</th>" else ""}</tr>")

                fun row(name: String, value: String, baseValue: String? = null, cls: String = "") {
                    append("<tr${if (cls.isNotEmpty()) " class='$cls'" else ""}><td>$name</td><td>$value</td>")
                    if (base != null) append("<td>${baseValue ?: ""}</td>")
                    appendLine("</tr>")
                }

                val greenPct = String.format("%.1f%%", agg.greenRate * 100)
                val wgPct = String.format("%.1f%%", agg.wrongGreenRate * 100)
                val greenCls = if (agg.greenRate >= 0.7) "good" else "warn"
                val wgCls = if (agg.wrongGreenRate <= 0.02) "good" else "bad"

                row("Photos", "${agg.photoCount}", base?.photoCount?.toString())
                row("Legible games", "${agg.legibleCount}", base?.legibleCount?.toString())
                row("Green found", "${agg.greenFound} ($greenPct)",
                    base?.let { "${it.greenFound} (${String.format("%.1f%%", it.greenRate * 100)})" }, greenCls)
                row("Wrong greens", "${agg.wrongGreens} ($wgPct)",
                    base?.let { "${it.wrongGreens} (${String.format("%.1f%%", it.wrongGreenRate * 100)})" }, wgCls)
                row("Oranges", "${agg.oranges}", base?.oranges?.toString())
                row("Reds", "${agg.reds}", base?.reds?.toString())
                row("Total boxes", "${agg.totalBoxes}", base?.totalBoxes?.toString())
                appendLine("</table>")
            }

            aggTable("Overall", run.overall, baseline?.overall)
            if (run.tune.photoCount > 0) aggTable("Tune", run.tune, baseline?.tune)
            if (run.holdout.photoCount > 0) aggTable("Holdout", run.holdout, baseline?.holdout)

            // Per-photo details
            appendLine("<h2>Per-Photo Results</h2>")
            appendLine("<table>")
            appendLine("<tr><th>Photo</th><th>Set</th><th>Legible</th><th>Green</th><th>WG</th><th>Orange</th><th>Red</th><th>ms</th></tr>")
            for (m in run.photos) {
                val cls = if (m.wrongGreens > 0) " class='regression'" else ""
                appendLine("<tr$cls class='photo-row'>")
                appendLine("<td>${m.photo}</td><td>${m.set}</td><td>${m.legibleCount}</td>")
                appendLine("<td>${m.greenFound}</td><td>${m.wrongGreens}</td>")
                appendLine("<td>${m.oranges}</td><td>${m.reds}</td><td>${m.elapsedMs}</td></tr>")
            }
            appendLine("</table>")

            // Wrong greens detail
            val withWG = run.photos.filter { it.wrongGreens > 0 }
            if (withWG.isNotEmpty()) {
                appendLine("<h2>Wrong Greens</h2>")
                for (m in withWG) {
                    appendLine("<details><summary class='bad'>${m.photo} (${m.wrongGreens})</summary><ul>")
                    for (n in m.wrongGreenNames) appendLine("<li>$n</li>")
                    appendLine("</ul></details>")
                }
            }

            // Missed games detail
            val withMissed = run.photos.filter { it.missedNames.isNotEmpty() }
            if (withMissed.isNotEmpty()) {
                appendLine("<h2>Missed Games (legible but not green)</h2>")
                for (m in withMissed) {
                    appendLine("<details><summary>${m.photo} (${m.missedNames.size}/${m.legibleCount})</summary><ul>")
                    for (n in m.missedNames) appendLine("<li>$n</li>")
                    appendLine("</ul></details>")
                }
            }

            appendLine("</body></html>")
        }

        val reportFile = File(outputDir, "eval-report.html")
        reportFile.writeText(html)
        println()
        println("HTML report: ${reportFile.path}")
    }

    private fun annotateFrame(image: BufferedImage, result: Pipeline.FrameResult): BufferedImage {
        val annotated = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = annotated.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
        )

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
}
