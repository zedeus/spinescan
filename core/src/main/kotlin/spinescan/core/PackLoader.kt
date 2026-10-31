package spinescan.core

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Loads pack data from the SQLite pack file into a TrigramIndex.
 */
object PackLoader {

    data class PackGame(
        val bggId: Int,
        val name: String,
        val year: Int?,
        val isExpansion: Boolean,
        val baseGameId: Int?,
        val minPlayers: Int?,
        val maxPlayers: Int?,
        val minPlayersBest: Int?,
        val maxPlayersBest: Int?,
        val minTime: Int?,
        val maxTime: Int?,
        val complexity: Double?,
        val avgRating: Double?,
        val numVotes: Int,
        val rank: Int?,
        val imageUrl: String?,
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
                if (rs.next()) rs.getInt(1) else 1
            }
        }

        val games = mutableMapOf<Int, PackGame>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM games").use { rs ->
                while (rs.next()) {
                    val game = PackGame(
                        bggId = rs.getInt("bgg_id"),
                        name = rs.getString("name"),
                        year = rs.intOrNull("year"),
                        isExpansion = rs.getInt("is_expansion") == 1,
                        baseGameId = rs.intOrNull("base_game_id"),
                        minPlayers = rs.intOrNull("min_players"),
                        maxPlayers = rs.intOrNull("max_players"),
                        minPlayersBest = rs.intOrNull("min_players_best"),
                        maxPlayersBest = rs.intOrNull("max_players_best"),
                        minTime = rs.intOrNull("min_time"),
                        maxTime = rs.intOrNull("max_time"),
                        complexity = rs.doubleOrNull("complexity"),
                        avgRating = rs.doubleOrNull("avg_rating"),
                        numVotes = rs.getInt("num_votes"),
                        rank = rs.intOrNull("rank"),
                        imageUrl = rs.getString("image_url"),
                    )
                    games[game.bggId] = game
                }
            }
        }

        val index = TrigramIndex()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM names").use { rs ->
                while (rs.next()) {
                    val bggId = rs.getInt("bgg_id")
                    val game = games[bggId] ?: continue
                    index.add(
                        bggId = bggId,
                        rawName = rs.getString("name"),
                        normName = rs.getString("name_norm"),
                        normStripped = rs.getString("name_stripped"),
                        popularity = game.numVotes,
                    )
                }
            }
        }

        return Pack(version = version, index = index, games = games)
    }

    private fun ResultSet.intOrNull(col: String): Int? =
        getObject(col)?.let { (it as Number).toInt() }

    private fun ResultSet.doubleOrNull(col: String): Double? =
        getObject(col)?.let { (it as Number).toDouble() }
}
