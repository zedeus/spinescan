package spinescan.harness

import spinescan.core.Normalize
import spinescan.core.PackLoader
import spinescan.core.TrigramIndex
import java.sql.DriverManager

/**
 * Loads the full games.db (135k games, 207k names) into a TrigramIndex
 * for harness use. The phone uses the 10k pack; the harness searches everything.
 */
object FullDbLoader {

    fun load(path: String): PackLoader.Pack {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        return conn.use { c ->
            // Load games (minimal fields — just what matching needs)
            val games = mutableMapOf<Int, PackLoader.PackGame>()
            c.createStatement().use { stmt ->
                stmt.executeQuery("""
                    SELECT bgg_id, name, year, is_expansion, base_game_id,
                           num_votes, avg_rating, rank, image_url
                    FROM games
                """).use { rs ->
                    while (rs.next()) {
                        val bggId = rs.getInt("bgg_id")
                        games[bggId] = PackLoader.PackGame(
                            bggId = bggId,
                            name = rs.getString("name"),
                            year = rs.getObject("year")?.let { (it as Number).toInt() },
                            isExpansion = rs.getInt("is_expansion") == 1,
                            baseGameId = rs.getObject("base_game_id")?.let { (it as Number).toInt() },
                            minPlayers = null,
                            maxPlayers = null,
                            minPlayersBest = null,
                            maxPlayersBest = null,
                            minTime = null,
                            maxTime = null,
                            complexity = null,
                            avgRating = rs.getObject("avg_rating")?.let { (it as Number).toDouble() },
                            numVotes = rs.getObject("num_votes")?.let { (it as Number).toInt() } ?: 0,
                            rank = rs.getObject("rank")?.let { (it as Number).toInt() },
                            imageUrl = rs.getString("image_url"),
                        )
                    }
                }
            }

            // Build trigram index from all names
            val index = TrigramIndex()
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT bgg_id, name, name_norm FROM names").use { rs ->
                    while (rs.next()) {
                        val bggId = rs.getInt("bgg_id")
                        val game = games[bggId] ?: continue
                        val normName = rs.getString("name_norm")
                        val (stripped, _) = Normalize.stripEditionWords(normName)
                        index.add(
                            bggId = bggId,
                            rawName = rs.getString("name"),
                            normName = normName,
                            normStripped = stripped,
                            popularity = game.numVotes,
                        )
                    }
                }
            }

            PackLoader.Pack(version = 0, index = index, games = games)
        }
    }
}
