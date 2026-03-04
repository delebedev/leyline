package leyline.frontdoor

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

object PlayerDb {
    private val log = LoggerFactory.getLogger(PlayerDb::class.java)
    private var dbPath: String? = null

    data class Player(
        val playerId: String,
        val screenName: String,
        val preferences: String,
        val inventory: String,
        val cosmetics: String,
        val rankInfo: String,
    )

    data class DeckRow(
        val deckId: String,
        val playerId: String,
        val name: String,
        val tileId: Int,
        val format: String,
        val cards: String,
    )

    fun init(dbFile: File): Boolean {
        dbPath = dbFile.absolutePath
        dbFile.parentFile?.mkdirs()
        conn { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS players (
                        player_id   TEXT PRIMARY KEY,
                        screen_name TEXT NOT NULL DEFAULT 'Planeswalker',
                        preferences TEXT NOT NULL DEFAULT '{}',
                        inventory   TEXT NOT NULL DEFAULT '{}',
                        cosmetics   TEXT NOT NULL DEFAULT '{}',
                        rank_info   TEXT NOT NULL DEFAULT '{}',
                        created_at  TEXT DEFAULT (datetime('now'))
                    )
                    """,
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS decks (
                        deck_id    TEXT PRIMARY KEY,
                        player_id  TEXT NOT NULL,
                        name       TEXT NOT NULL,
                        tile_id    INTEGER NOT NULL DEFAULT 0,
                        format     TEXT NOT NULL DEFAULT 'Standard',
                        cards      TEXT NOT NULL DEFAULT '{}',
                        updated_at TEXT DEFAULT (datetime('now'))
                    )
                    """,
                )
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_decks_player ON decks(player_id)")
            }
        }
        log.info("PlayerDb initialized: {}", dbFile)
        return true
    }

    fun isInitialized(): Boolean = dbPath != null

    fun upsertPlayer(playerId: String, screenName: String = "Planeswalker") {
        conn { c ->
            c.prepareStatement(
                """
                INSERT INTO players (player_id, screen_name) VALUES (?, ?)
                ON CONFLICT(player_id) DO NOTHING
                """,
            ).use { s ->
                s.setString(1, playerId)
                s.setString(2, screenName)
                s.executeUpdate()
            }
        }
    }

    fun getPlayer(playerId: String): Player? = conn { c ->
        c.prepareStatement("SELECT * FROM players WHERE player_id = ?").use { s ->
            s.setString(1, playerId)
            val rs = s.executeQuery()
            if (rs.next()) {
                Player(
                    playerId = rs.getString("player_id"),
                    screenName = rs.getString("screen_name"),
                    preferences = rs.getString("preferences"),
                    inventory = rs.getString("inventory"),
                    cosmetics = rs.getString("cosmetics"),
                    rankInfo = rs.getString("rank_info"),
                )
            } else {
                null
            }
        }
    }

    fun getPreferences(playerId: String): String? = conn { c ->
        c.prepareStatement("SELECT preferences FROM players WHERE player_id = ?").use { s ->
            s.setString(1, playerId)
            val rs = s.executeQuery()
            if (rs.next()) rs.getString("preferences") else null
        }
    }

    fun updatePreferences(playerId: String, preferencesJson: String) {
        conn { c ->
            c.prepareStatement("UPDATE players SET preferences = ? WHERE player_id = ?").use { s ->
                s.setString(1, preferencesJson)
                s.setString(2, playerId)
                s.executeUpdate()
            }
        }
    }

    fun updateInventory(playerId: String, inventoryJson: String) {
        conn { c ->
            c.prepareStatement("UPDATE players SET inventory = ? WHERE player_id = ?").use { s ->
                s.setString(1, inventoryJson)
                s.setString(2, playerId)
                s.executeUpdate()
            }
        }
    }

    fun updateCosmetics(playerId: String, cosmeticsJson: String) {
        conn { c ->
            c.prepareStatement("UPDATE players SET cosmetics = ? WHERE player_id = ?").use { s ->
                s.setString(1, cosmeticsJson)
                s.setString(2, playerId)
                s.executeUpdate()
            }
        }
    }

    // --- Deck CRUD ---

    fun upsertDeck(deckId: String, playerId: String, name: String, tileId: Int, format: String, cards: String) {
        conn { c ->
            c.prepareStatement(
                """
                INSERT INTO decks (deck_id, player_id, name, tile_id, format, cards, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(deck_id) DO UPDATE SET
                    name = excluded.name,
                    tile_id = excluded.tile_id,
                    format = excluded.format,
                    cards = excluded.cards,
                    updated_at = datetime('now')
                """,
            ).use { s ->
                s.setString(1, deckId)
                s.setString(2, playerId)
                s.setString(3, name)
                s.setInt(4, tileId)
                s.setString(5, format)
                s.setString(6, cards)
                s.executeUpdate()
            }
        }
    }

    fun getDeck(deckId: String): DeckRow? = conn { c ->
        c.prepareStatement("SELECT * FROM decks WHERE deck_id = ?").use { s ->
            s.setString(1, deckId)
            val rs = s.executeQuery()
            if (rs.next()) readDeckRow(rs) else null
        }
    }

    fun getDecksForPlayer(playerId: String): List<DeckRow> = conn { c ->
        c.prepareStatement("SELECT * FROM decks WHERE player_id = ? ORDER BY updated_at DESC").use { s ->
            s.setString(1, playerId)
            val rs = s.executeQuery()
            val result = mutableListOf<DeckRow>()
            while (rs.next()) result.add(readDeckRow(rs))
            result
        }
    }

    fun deleteDeck(deckId: String) {
        conn { c ->
            c.prepareStatement("DELETE FROM decks WHERE deck_id = ?").use { s ->
                s.setString(1, deckId)
                s.executeUpdate()
            }
        }
    }

    /** Reset for testing — allows re-init with a different DB file. */
    internal fun reset() {
        dbPath = null
    }

    private fun readDeckRow(rs: ResultSet) = DeckRow(
        deckId = rs.getString("deck_id"),
        playerId = rs.getString("player_id"),
        name = rs.getString("name"),
        tileId = rs.getInt("tile_id"),
        format = rs.getString("format"),
        cards = rs.getString("cards"),
    )

    private fun <T> conn(block: (Connection) -> T): T {
        val path = dbPath ?: error("PlayerDb not initialized")
        return DriverManager.getConnection("jdbc:sqlite:$path").use(block)
    }
}
