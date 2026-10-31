package spinescan.harness

import spinescan.core.Normalize
import spinescan.core.PackLoader
import spinescan.core.TrigramIndex
import java.sql.DriverManager

/**
 * Loads the full games.db (135k games, 207k names) into a TrigramIndex
 * for harness use. The phone's pack has every game but only primary names;
 * the harness searches all names directly.
 */
object FullDbLoader {

    fun load(path: String): PackLoader.Pack {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        return conn.use { c ->
            // Load games (minimal fields — just what matching needs)
            val games = mutableMapOf<Int, PackLoader.PackGame>()
            c.createStatement().use { stmt ->
                stmt.executeQuery("""
                    SELECT bgg_id, name, year, num_votes
                    FROM games
                """).use { rs ->
                    while (rs.next()) {
                        val bggId = rs.getInt("bgg_id")
                        games[bggId] = PackLoader.PackGame(
                            bggId = bggId,
                            name = rs.getString("name"),
                            year = rs.getObject("year")?.let { (it as Number).toInt() },
                            numVotes = rs.getObject("num_votes")?.let { (it as Number).toInt() } ?: 0,
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
