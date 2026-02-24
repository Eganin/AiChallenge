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

}
