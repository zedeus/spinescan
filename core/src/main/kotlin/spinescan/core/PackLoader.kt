package spinescan.core

import java.sql.Connection
import java.sql.DriverManager

/**
 * Loads pack data from the SQLite pack file into a TrigramIndex.
 *
 * Pack format v2 is names-only: one primary name per game with ID, year
 * and popularity. Alternate names, descriptions and library fields live
 * behind the game-data API.
 */
object PackLoader {

    const val PACK_VERSION = 2

    data class PackGame(
        val bggId: Int,
        val name: String,
        val year: Int?,
        val numVotes: Int,
    )

    data class Pack(
        val version: Int,
        val index: TrigramIndex,
        val games: Map<Int, PackGame>,
    )

    /**
     * Load a pack from a SQLite database file. Opens and closes its own connection.
     */
    fun load(path: String): Pack {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        return conn.use { load(it) }
    }

    /**
     * Load a pack from an existing JDBC connection. Does not close the connection.
     */
    fun load(conn: Connection): Pack {
        val version = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT value FROM pack_meta WHERE key = 'version'").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        require(version == PACK_VERSION) {
            "Unsupported pack version $version (expected $PACK_VERSION)"
        }

        val games = mutableMapOf<Int, PackGame>()
        val index = TrigramIndex()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT bgg_id, name, name_norm, name_stripped, year, num_votes FROM games"
            ).use { rs ->
                while (rs.next()) {
                    val bggId = rs.getInt("bgg_id")
                    val name = rs.getString("name")
                    val numVotes = rs.getInt("num_votes")
                    games[bggId] = PackGame(
                        bggId = bggId,
                        name = name,
                        year = rs.getObject("year")?.let { (it as Number).toInt() },
                        numVotes = numVotes,
                    )
                    index.add(
                        bggId = bggId,
                        rawName = name,
                        normName = rs.getString("name_norm"),
                        normStripped = rs.getString("name_stripped"),
                        popularity = numVotes,
                    )
                }
            }
        }

        return Pack(version = version, index = index, games = games)
    }
}
