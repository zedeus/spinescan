package spinescan.core.api

import spinescan.core.Normalize
import java.io.File
import java.sql.DriverManager

/** Builds small v2 pack files for tests. Hand-invented rows only. */
object TestPacks {

    data class Game(val bggId: Int, val name: String, val year: Int?, val numVotes: Int)

    val DEFAULT_GAMES = listOf(
        Game(13, "Catan", 1995, 100_000),
        Game(174430, "Gloomhaven", 2017, 60_000),
        Game(68448, "7 Wonders", 2010, 90_000),
    )

    fun write(file: File, games: List<Game> = DEFAULT_GAMES, version: Int = 2) {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE pack_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.executeUpdate(
                    """CREATE TABLE games (
                        bgg_id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                        name_norm TEXT NOT NULL, name_stripped TEXT NOT NULL,
                        year INTEGER, num_votes INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
            conn.prepareStatement("INSERT INTO pack_meta VALUES (?, ?)").use { ps ->
                ps.setString(1, "version"); ps.setString(2, version.toString()); ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO games VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (g in games) {
                    val norm = Normalize.normalize(g.name)
                    val (stripped, _) = Normalize.stripEditionWords(norm)
                    ps.setInt(1, g.bggId)
                    ps.setString(2, g.name)
                    ps.setString(3, norm)
                    ps.setString(4, stripped)
                    if (g.year != null) ps.setInt(5, g.year) else ps.setObject(5, null)
                    ps.setInt(6, g.numVotes)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun gzipped(games: List<Game> = DEFAULT_GAMES): ByteArray {
        val tmp = File.createTempFile("testpack", ".db")
        try {
            tmp.delete()
            write(tmp, games)
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { it.write(tmp.readBytes()) }
            return out.toByteArray()
        } finally {
            tmp.delete()
        }
    }
}
