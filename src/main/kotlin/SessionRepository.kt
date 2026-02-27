package org.example

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class SessionRecord(
    val id: Long,
    val name: String,
    val systemPrompt: String?,
    val strategy: StrategyType = StrategyType.SUMMARY
)

class SessionRepository(private val dbPath: String = "agent.db") {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("PRAGMA foreign_keys = ON")
        }
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    name             TEXT UNIQUE NOT NULL,
                    system_prompt    TEXT,
                    created_at       TEXT NOT NULL,
                    summary          TEXT,
                    summarized_up_to INTEGER NOT NULL DEFAULT 0
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
        // Migration: add columns to existing DBs that predate this schema
        listOf(
            "ALTER TABLE sessions ADD COLUMN summary TEXT",
            "ALTER TABLE sessions ADD COLUMN summarized_up_to INTEGER NOT NULL DEFAULT 0"
        ).forEach { ddl ->
            try { connection.createStatement().use { it.executeUpdate(ddl) } } catch (_: Exception) {}
        }
        // New migrations for strategies, facts, branches
        listOf(
            "ALTER TABLE sessions ADD COLUMN strategy TEXT NOT NULL DEFAULT 'SUMMARY'",
            "ALTER TABLE sessions ADD COLUMN facts TEXT",
            "ALTER TABLE sessions ADD COLUMN active_branch_id INTEGER",
            "ALTER TABLE messages ADD COLUMN branch_id INTEGER",
            """CREATE TABLE IF NOT EXISTS branches (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        name       TEXT NOT NULL,
        checkpoint INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL
    )"""
        ).forEach { ddl ->
            try { connection.createStatement().use { it.executeUpdate(ddl) } } catch (_: Exception) {}
        }
    }

    fun listSessions(): List<SessionRecord> {
        val results = mutableListOf<SessionRecord>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT id, name, system_prompt, strategy FROM sessions ORDER BY created_at").use { rs ->
                while (rs.next()) {
                    val strategyType = try {
                        StrategyType.valueOf(rs.getString("strategy") ?: "SUMMARY")
                    } catch (_: Exception) { StrategyType.SUMMARY }
                    results += SessionRecord(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        systemPrompt = rs.getString("system_prompt"),
                        strategy = strategyType
                    )
                }
            }
        }
        return results
    }

    fun createSession(name: String, systemPrompt: String?, strategy: StrategyType = StrategyType.SUMMARY): SessionRecord {
        connection.prepareStatement(
            "INSERT INTO sessions (name, system_prompt, created_at, strategy) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setString(1, name)
            stmt.setString(2, systemPrompt)
            stmt.setString(3, Instant.now().toString())
            stmt.setString(4, strategy.name)
            stmt.executeUpdate()
        }
        return connection.prepareStatement("SELECT id FROM sessions WHERE name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                rs.next()
                SessionRecord(rs.getLong("id"), name, systemPrompt, strategy)
            }
        }
    }

    fun loadMessages(sessionId: Long, branchId: Long? = null): List<Message> {
        val sql = if (branchId == null)
            "SELECT role, content FROM messages WHERE session_id = ? AND branch_id IS NULL ORDER BY position"
        else
            "SELECT role, content FROM messages WHERE session_id = ? AND branch_id = ? ORDER BY position"
        val results = mutableListOf<Message>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, sessionId)
            if (branchId != null) stmt.setLong(2, branchId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) results += Message(rs.getString("role"), rs.getString("content"))
            }
        }
        return results
    }

    fun appendMessages(sessionId: Long, messages: List<Message>, startPosition: Int, branchId: Long? = null) {
        connection.prepareStatement(
            "INSERT INTO messages (session_id, role, content, position, branch_id) VALUES (?, ?, ?, ?, ?)"
        ).use { stmt ->
            messages.forEachIndexed { index, msg ->
                stmt.setLong(1, sessionId)
                stmt.setString(2, msg.role)
                stmt.setString(3, msg.content)
                stmt.setInt(4, startPosition + index)
                if (branchId != null) stmt.setLong(5, branchId)
                else stmt.setNull(5, java.sql.Types.INTEGER)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }


    fun loadSummaryState(sessionId: Long): Pair<String?, Int> {
        connection.prepareStatement(
            "SELECT summary, summarized_up_to FROM sessions WHERE id = ?"
        ).use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getString("summary") to rs.getInt("summarized_up_to")
            }
        }
        return null to 0
    }

    fun updateSummaryState(sessionId: Long, summary: String?, summarizedUpTo: Int) {
        connection.prepareStatement(
            "UPDATE sessions SET summary = ?, summarized_up_to = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setString(1, summary)
            stmt.setInt(2, summarizedUpTo)
            stmt.setLong(3, sessionId)
            stmt.executeUpdate()
        }
    }

    fun clearMessages(sessionId: Long) {
        connection.prepareStatement("DELETE FROM messages WHERE session_id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM branches WHERE session_id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeUpdate()
        }
        updateSummaryState(sessionId, null, 0)
        updateFacts(sessionId, null)
        updateActiveBranch(sessionId, null)
    }

    fun deleteSession(sessionId: Long) {
        connection.prepareStatement("DELETE FROM sessions WHERE id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeUpdate()
        }
    }

    fun updateFacts(sessionId: Long, factsJson: String?) {
        connection.prepareStatement("UPDATE sessions SET facts = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, factsJson)
            stmt.setLong(2, sessionId)
            stmt.executeUpdate()
        }
    }

    fun loadFacts(sessionId: Long): String? {
        connection.prepareStatement("SELECT facts FROM sessions WHERE id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getString("facts")
            }
        }
        return null
    }

    fun createBranch(sessionId: Long, name: String, checkpoint: Int): Long {
        connection.prepareStatement(
            "INSERT INTO branches (session_id, name, checkpoint, created_at) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.setString(2, name)
            stmt.setInt(3, checkpoint)
            stmt.setString(4, Instant.now().toString())
            stmt.executeUpdate()
        }
        return connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    data class BranchInfo(val id: Long, val name: String, val checkpoint: Int)

    fun loadBranches(sessionId: Long): List<BranchInfo> {
        val results = mutableListOf<BranchInfo>()
        connection.prepareStatement(
            "SELECT id, name, checkpoint FROM branches WHERE session_id = ? ORDER BY id"
        ).use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeQuery().use { rs ->
                while (rs.next())
                    results += BranchInfo(rs.getLong("id"), rs.getString("name"), rs.getInt("checkpoint"))
            }
        }
        return results
    }

    fun updateActiveBranch(sessionId: Long, branchId: Long?) {
        connection.prepareStatement("UPDATE sessions SET active_branch_id = ? WHERE id = ?").use { stmt ->
            if (branchId != null) stmt.setLong(1, branchId)
            else stmt.setNull(1, java.sql.Types.INTEGER)
            stmt.setLong(2, sessionId)
            stmt.executeUpdate()
        }
    }

    fun loadActiveBranchId(sessionId: Long): Long? {
        connection.prepareStatement("SELECT active_branch_id FROM sessions WHERE id = ?").use { stmt ->
            stmt.setLong(1, sessionId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getLong("active_branch_id").takeIf { !rs.wasNull() }
            }
        }
        return null
    }

    fun close() = connection.close()
}
