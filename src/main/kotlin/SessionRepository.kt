package org.example

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class SessionRecord(val id: Long, val name: String, val systemPrompt: String?)

class SessionRepository(private val dbPath: String = "agent.db") {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("PRAGMA foreign_keys = ON")
        }
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT UNIQUE NOT NULL,
                    system_prompt TEXT,
                    created_at   TEXT NOT NULL
                )
            """.trimIndent())
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS messages (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    role       TEXT NOT NULL,
                    content    TEXT NOT NULL,
                    position   INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    fun listSessions(): List<SessionRecord> {
        val results = mutableListOf<SessionRecord>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT id, name, system_prompt FROM sessions ORDER BY created_at").use { rs ->
                while (rs.next()) {
                    results += SessionRecord(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        systemPrompt = rs.getString("system_prompt")
                    )
                }
            }
        }
        return results
    }

    fun createSession(name: String, systemPrompt: String?): SessionRecord {
        connection.prepareStatement(
            "INSERT INTO sessions (name, system_prompt, created_at) VALUES (?, ?, ?)"
        ).use { stmt ->
            stmt.setString(1, name)
            stmt.setString(2, systemPrompt)
            stmt.setString(3, Instant.now().toString())
            stmt.executeUpdate()
        }
        return connection.prepareStatement("SELECT id FROM sessions WHERE name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                SessionRecord(rs.getLong("id"), name, systemPrompt)
            }
        }
    }

    fun loadMessages(sessionId: Long): List<Message> {
        val results = mutableListOf<Message>()
        connection.prepareStatement(
            "SELECT role, content FROM messages WHERE session_id = ? ORDER BY position"
        ).use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results += Message(rs.getString("role"), rs.getString("content"))
                }
            }
        }
        return results
    }

    fun appendMessages(sessionId: Long, messages: List<Message>, startPosition: Int) {
        connection.prepareStatement(
            "INSERT INTO messages (session_id, role, content, position) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            messages.forEachIndexed { index, msg ->
                stmt.setLong(1, sessionId)
                stmt.setString(2, msg.role)
                stmt.setString(3, msg.content)
                stmt.setInt(4, startPosition + index)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }


    fun clearMessages(sessionId: Long) {
        connection.prepareStatement("DELETE FROM messages WHERE session_id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeUpdate()
        }
    }

    fun deleteSession(sessionId: Long) {
        connection.prepareStatement("DELETE FROM sessions WHERE id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeUpdate()
        }
    }

    fun close() = connection.close()
}
