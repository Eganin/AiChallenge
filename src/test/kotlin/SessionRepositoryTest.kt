package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRepositoryTest {

    @Test
    fun `repository initialises schema without throwing`() {
        val repo = SessionRepository(":memory:")
        assertNotNull(repo)
        repo.close()
    }

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
        val sessionId = session.id
        repo.deleteSession(sessionId)
        assertEquals(0, repo.listSessions().size)
        assertEquals(0, repo.loadMessages(sessionId).size)
        repo.close()
    }

    @Test
    fun `createSession stores strategy`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("test-strategy", null, StrategyType.SLIDING_WINDOW)
        assertEquals(StrategyType.SLIDING_WINDOW, record.strategy)
        val loaded = repo.listSessions().first { it.name == "test-strategy" }
        assertEquals(StrategyType.SLIDING_WINDOW, loaded.strategy)
        repo.close()
    }

    @Test
    fun `updateFacts and loadFacts round trip`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("facts-test", null)
        repo.updateFacts(record.id, """{"цель":"тест"}""")
        assertEquals("""{"цель":"тест"}""", repo.loadFacts(record.id))
        repo.close()
    }

    @Test
    fun `loadFacts returns null when not set`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("no-facts", null)
        assertNull(repo.loadFacts(record.id))
        repo.close()
    }

    @Test
    fun `createBranch and loadBranches round trip`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("branch-test", null)
        val branchId = repo.createBranch(record.id, "alt", 4)
        assertTrue(branchId > 0)
        val branches = repo.loadBranches(record.id)
        assertEquals(1, branches.size)
        assertEquals("alt", branches[0].name)
        assertEquals(4, branches[0].checkpoint)
        repo.close()
    }

    @Test
    fun `appendMessages with branchId stored and loadMessages filtered`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("br-msg-test", null)
        val branchId = repo.createBranch(record.id, "b1", 0)
        val trunkMsgs = listOf(Message("user", "trunk"))
        val branchMsgs = listOf(Message("user", "branch"))
        repo.appendMessages(record.id, trunkMsgs, 0, null)
        repo.appendMessages(record.id, branchMsgs, 0, branchId)
        val trunk = repo.loadMessages(record.id, null)
        val branch = repo.loadMessages(record.id, branchId)
        assertEquals(1, trunk.size)
        assertEquals("trunk", trunk[0].content)
        assertEquals(1, branch.size)
        assertEquals("branch", branch[0].content)
        repo.close()
    }

    @Test
    fun `updateActiveBranch and loadActiveBranchId`() {
        val repo = SessionRepository(":memory:")
        val record = repo.createSession("active-br", null)
        val id = repo.createBranch(record.id, "main", 0)
        repo.updateActiveBranch(record.id, id)
        assertEquals(id, repo.loadActiveBranchId(record.id))
        repo.updateActiveBranch(record.id, null)
        assertNull(repo.loadActiveBranchId(record.id))
        repo.close()
    }

}
