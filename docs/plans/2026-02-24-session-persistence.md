# Session Persistence Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist conversation history in SQLite across restarts; support multiple named sessions with an interactive startup menu.

**Architecture:** New `SessionRepository` wraps JDBC/SQLite for all I/O. `CliAgent` gains a session-selection menu at startup, loads history into `ConversationSession`, and appends new messages after each exchange. `ConversationSession` is not modified.

**Tech Stack:** Kotlin, JDBC, `org.xerial:sqlite-jdbc:3.47.1.0`, `kotlin.test` (no mocking library — stubs via subclassing, same pattern as existing tests)

---

### Task 1: Add SQLite dependency

**Files:**
- Modify: `build.gradle.kts`

**Step 1: Add dependency**

In `build.gradle.kts`, inside `dependencies {}`, add:

```kotlin
implementation("org.xerial:sqlite-jdbc:3.47.1.0")
```

**Step 2: Verify sync**

Run: `./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep sqlite`
Expected output contains: `org.xerial:sqlite-jdbc:3.47.1.0`

**Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add sqlite-jdbc dependency"
```

---

### Task 2: SessionRepository skeleton — schema creation

**Files:**
- Create: `src/main/kotlin/SessionRepository.kt`
- Create: `src/test/kotlin/SessionRepositoryTest.kt`

**Step 1: Write the failing test**

Create `src/test/kotlin/SessionRepositoryTest.kt`:

```kotlin
package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionRepositoryTest {

    @Test
    fun `repository initialises schema without throwing`() {
        val repo = SessionRepository(":memory:")
        assertNotNull(repo)
        repo.close()
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: compilation error — `SessionRepository` does not exist

**Step 3: Create SessionRepository skeleton**

Create `src/main/kotlin/SessionRepository.kt`:

```kotlin
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

    fun close() = connection.close()
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add src/main/kotlin/SessionRepository.kt src/test/kotlin/SessionRepositoryTest.kt
git commit -m "feat: add SessionRepository skeleton with schema"
```

---

### Task 3: Implement createSession and listSessions

**Files:**
- Modify: `src/main/kotlin/SessionRepository.kt`
- Modify: `src/test/kotlin/SessionRepositoryTest.kt`

**Step 1: Write the failing tests**

Add to `SessionRepositoryTest` (inside the class):

```kotlin
    @Test
    fun `listSessions returns empty list when no sessions exist`() {
        val repo = SessionRepository(":memory:")
        assertEquals(0, repo.listSessions().size)
        repo.close()
    }

    @Test
    fun `createSession saves and returns SessionRecord`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("work", "You are helpful")
        assertEquals("work", record.name)
        assertEquals("You are helpful", record.systemPrompt)
        repo.close()
    }

    @Test
    fun `createSession with null systemPrompt stores null`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("test", null)
        assertEquals(null, record.systemPrompt)
        repo.close()
    }

    @Test
    fun `listSessions returns all created sessions in order`() {
        val repo = SessionRepository(":memory:")
        repo.createSession("work", null)
        repo.createSession("personal", "Be casual")
        val sessions = repo.listSessions()
        assertEquals(2, sessions.size)
        assertEquals("work", sessions[0].name)
        assertEquals("personal", sessions[1].name)
        repo.close()
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: compilation errors — `listSessions` and `createSession` do not exist

**Step 3: Implement listSessions and createSession**

Add to `SessionRepository.kt`, before `fun close()`:

```kotlin
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
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, 5 tests passed

**Step 5: Commit**

```bash
git add src/main/kotlin/SessionRepository.kt src/test/kotlin/SessionRepositoryTest.kt
git commit -m "feat: implement createSession and listSessions"
```

---

### Task 4: Implement loadMessages and appendMessages

**Files:**
- Modify: `src/main/kotlin/SessionRepository.kt`
- Modify: `src/test/kotlin/SessionRepositoryTest.kt`

**Step 1: Write the failing tests**

Add to `SessionRepositoryTest`:

```kotlin
    @Test
    fun `loadMessages returns empty list for new session`() {
        val repo = SessionRepository(":memory:")
        val session = repo.createSession("s", null)
        assertEquals(0, repo.loadMessages(session.id).size)
        repo.close()
    }

    @Test
    fun `appendMessages and loadMessages round-trip`() {
        val repo = SessionRepository(":memory:")
        val session = repo.createSession("s", null)
        val msgs = listOf(Message("user", "hello"), Message("assistant", "hi"))
        repo.appendMessages(session.id, msgs, startPosition = 0)
        val loaded = repo.loadMessages(session.id)
        assertEquals(2, loaded.size)
        assertEquals(Message("user", "hello"), loaded[0])
        assertEquals(Message("assistant", "hi"), loaded[1])
        repo.close()
    }

    @Test
    fun `appendMessages preserves order across multiple calls`() {
        val repo = SessionRepository(":memory:")
        val session = repo.createSession("s", null)
        repo.appendMessages(session.id, listOf(Message("user", "first"), Message("assistant", "r1")), startPosition = 0)
        repo.appendMessages(session.id, listOf(Message("user", "second"), Message("assistant", "r2")), startPosition = 2)
        val loaded = repo.loadMessages(session.id)
        assertEquals(4, loaded.size)
        assertEquals("first", loaded[0].content)
        assertEquals("r1", loaded[1].content)
        assertEquals("second", loaded[2].content)
        assertEquals("r2", loaded[3].content)
        repo.close()
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: compilation errors — `loadMessages` and `appendMessages` do not exist

**Step 3: Implement loadMessages and appendMessages**

Add to `SessionRepository.kt`, before `fun close()`:

```kotlin
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
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, 8 tests passed

**Step 5: Commit**

```bash
git add src/main/kotlin/SessionRepository.kt src/test/kotlin/SessionRepositoryTest.kt
git commit -m "feat: implement loadMessages and appendMessages"
```

---

### Task 5: Implement clearMessages and deleteSession

**Files:**
- Modify: `src/main/kotlin/SessionRepository.kt`
- Modify: `src/test/kotlin/SessionRepositoryTest.kt`

**Step 1: Write the failing tests**

Add to `SessionRepositoryTest`:

```kotlin
    @Test
    fun `clearMessages removes all messages for session`() {
        val repo = SessionRepository(":memory:")
        val session = repo.createSession("s", null)
        repo.appendMessages(session.id, listOf(Message("user", "hi"), Message("assistant", "hello")), 0)
        repo.clearMessages(session.id)
        assertEquals(0, repo.loadMessages(session.id).size)
        repo.close()
    }

    @Test
    fun `deleteSession removes session and its messages`() {
        val repo = SessionRepository(":memory:")
        val session = repo.createSession("s", null)
        repo.appendMessages(session.id, listOf(Message("user", "hi")), 0)
        repo.deleteSession(session.id)
        assertEquals(0, repo.listSessions().size)
        repo.close()
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.example.SessionRepositoryTest" 2>&1 | tail -20`
Expected: compilation errors — `clearMessages` and `deleteSession` do not exist

**Step 3: Implement clearMessages and deleteSession**

Add to `SessionRepository.kt`, before `fun close()`:

```kotlin
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
```

**Step 4: Run all tests to confirm everything passes**

Run: `./gradlew test 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL, all tests pass

**Step 5: Commit**

```bash
git add src/main/kotlin/SessionRepository.kt src/test/kotlin/SessionRepositoryTest.kt
git commit -m "feat: implement clearMessages and deleteSession"
```

---

### Task 6: Update CliAgent with session menu

**Files:**
- Modify: `src/main/kotlin/CliAgent.kt`

**Step 1: Replace CliAgent.kt with the following**

```kotlin
// src/main/kotlin/CliAgent.kt
package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)
    private val repository = SessionRepository()

    fun run() {
        println("CLI AI Agent | ${client.model}")

        val (record, history) = selectOrCreateSession()
        val session = ConversationSession(client, record.systemPrompt)
        session.history.addAll(history)

        if (history.isNotEmpty()) {
            println("Загружена сессия «${record.name}» (${history.size / 2} обменов)\n")
        }

        println("Команды: exit — выйти, /reset — очистить историю, /delete — удалить сессию\n")

        var savedCount = history.size

        while (true) {
            print("Вы: ")
            val input = readlnOrNull()?.trim() ?: break
            when {
                input.lowercase() == "exit" -> break
                input == "/reset" -> {
                    repository.clearMessages(record.id)
                    session.reset()
                    savedCount = 0
                    println("История очищена.\n")
                }
                input == "/delete" -> {
                    repository.deleteSession(record.id)
                    println("Сессия «${record.name}» удалена.\n")
                    break
                }
                input.isEmpty() -> continue
                else -> {
                    val reply = session.chat(input)
                    repository.appendMessages(record.id, session.history.takeLast(2), savedCount)
                    savedCount += 2
                    println("\nАгент: $reply\n")
                }
            }
        }

        repository.close()
    }

    private fun selectOrCreateSession(): Pair<SessionRecord, List<Message>> {
        val sessions = repository.listSessions()

        if (sessions.isEmpty()) {
            println("Сохранённых сессий нет. Создаём первую.\n")
            return createNewSession() to emptyList()
        }

        println("Сессии:")
        sessions.forEachIndexed { i, s -> println("  ${i + 1}. ${s.name}") }
        println("  ${sessions.size + 1}. Новая сессия")
        println()

        while (true) {
            print("Выбор (1–${sessions.size + 1}): ")
            val choice = readlnOrNull()?.trim()?.toIntOrNull() ?: continue
            when {
                choice in 1..sessions.size -> {
                    val rec = sessions[choice - 1]
                    return rec to repository.loadMessages(rec.id)
                }
                choice == sessions.size + 1 -> return createNewSession() to emptyList()
                else -> println("Неверный выбор.\n")
            }
        }
    }

    private fun createNewSession(): SessionRecord {
        print("Имя сессии: ")
        val name = readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
        print("System prompt (Enter чтобы пропустить): ")
        val prompt = readlnOrNull()?.trim().takeIf { !it.isNullOrEmpty() }
        println()
        return repository.createSession(name, prompt)
    }
}
```

**Step 2: Run all tests to confirm nothing broke**

Run: `./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/CliAgent.kt
git commit -m "feat: update CliAgent with session menu and persistence"
```

---

### Task 7: Manual smoke test

**Step 1: Build and run**

Run: `./gradlew run`

**Step 2: First run — create a session**

1. No sessions listed → create session named `test` with any system prompt
2. Send 2 messages, type `exit`

**Step 3: Second run — resume session**

1. Session `test` appears in list → select it
2. Agent responds with awareness of previous conversation (history is restored)
3. Type `/reset` → history cleared
4. Type `/delete` → session removed

**Step 4: Confirm DB file exists**

Run: `ls -la agent.db`
Expected: file exists and has non-zero size
