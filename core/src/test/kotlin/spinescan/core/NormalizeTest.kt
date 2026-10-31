package spinescan.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals

/**
 * Test normalisation against the shared fixture file (fixtures/normalize.csv).
 */
class NormalizeTest {

    data class FixtureRow(
        val input: String,
        val normalized: String,
        val stripped: String,
        val editionWords: List<String>,
    )

    private fun loadFixture(): List<FixtureRow> {
        // Find fixtures/normalize.csv relative to project root
        val file = TestFixtures.find("fixtures/normalize.csv")
        val lines = file.readLines()
        val header = lines[0]
        require("input" in header) { "Unexpected header: $header" }
        return lines.drop(1).map { line ->
            val fields = parseCsvLine(line)
            FixtureRow(
                input = fields[0],
                normalized = fields[1],
                stripped = fields[2],
                editionWords = if (fields[3].isBlank()) emptyList() else fields[3].split(" "),
            )
        }
    }

    /** Minimal CSV parser that handles quoted fields. */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        var i = 0
        while (i <= line.length) {
            if (i == line.length) {
                fields.add("")
                break
            }
            if (line[i] == '"') {
                // Quoted field
                val sb = StringBuilder()
                i++ // skip opening quote
                while (i < line.length) {
                    if (line[i] == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"')
                            i += 2
                        } else {
                            i++ // skip closing quote
                            break
                        }
                    } else {
                        sb.append(line[i])
                        i++
                    }
                }
                fields.add(sb.toString())
                if (i < line.length && line[i] == ',') i++ // skip comma
            } else {
                val next = line.indexOf(',', i)
                if (next < 0) {
                    fields.add(line.substring(i))
                    break
                } else {
                    fields.add(line.substring(i, next))
                    i = next + 1
                }
            }
        }
        return fields
    }

    @TestFactory
    fun `normalize matches fixture`(): List<DynamicTest> =
        loadFixture().map { row ->
            DynamicTest.dynamicTest("normalize: ${row.input}") {
                assertEquals(row.normalized, Normalize.normalize(row.input))
            }
        }

    @TestFactory
    fun `strip edition words matches fixture`(): List<DynamicTest> =
        loadFixture().map { row ->
            DynamicTest.dynamicTest("strip: ${row.input}") {
                val (stripped, found) = Normalize.stripEditionWords(row.normalized)
                assertEquals(row.stripped, stripped)
                assertEquals(row.editionWords.sorted(), found.sorted())
            }
        }
}
