package spinescan.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The shared matcher fixture (fixtures/match-cases.json) pins the matching
 * behaviour the API's TypeScript port must agree with: for every case, the
 * top candidate here and on the server is the same game.
 */
class MatchParityTest {

    @Serializable
    data class FixtureName(val name: String, val is_primary: Boolean)

    @Serializable
    data class FixtureGame(
        val bgg_id: Int,
        val name: String,
        val year: Int? = null,
        val num_votes: Int,
        val names: List<FixtureName>,
    )

    @Serializable
    data class FixtureCase(val query: String, val top_bgg_id: Int)

    @Serializable
    data class Fixture(
        val comment: String = "",
        val games: List<FixtureGame>,
        val cases: List<FixtureCase>,
    )

    @Test
    fun `top candidate matches the fixture for every case`() {
        val fixture = Json { ignoreUnknownKeys = true }
            .decodeFromString<Fixture>(TestFixtures.find("fixtures/match-cases.json").readText())

        val index = TrigramIndex()
        for (game in fixture.games) {
            for (n in game.names) {
                val norm = Normalize.normalize(n.name)
                val (stripped, _) = Normalize.stripEditionWords(norm)
                index.add(game.bgg_id, n.name, norm, stripped, game.num_votes)
            }
        }

        for (case in fixture.cases) {
            val results = index.lookup(Normalize.normalize(case.query))
            assertTrue(
                results.isNotEmpty() && case.top_bgg_id in results[0].bggIds,
                "query '${case.query}' expected top ${case.top_bgg_id}, " +
                    "got ${results.firstOrNull()?.bggIds} (${results.firstOrNull()?.name})",
            )
        }
    }

}
