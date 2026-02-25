// src/test/kotlin/ConversationSessionTest.kt
package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationSessionTest {

    // A fake ClaudeClient replacement — we create a subclass that overrides ask()
    // We can't mock without a mocking library, so we use a lambda-based stub.
    private fun makeSession(
        systemPrompt: String? = null,
        windowSize: Int = 0,
        fakeReply: (List<Message>) -> String = { "reply" },
        fakeUsage: TokenUsage = TokenUsage(10, 5)
    ): ConversationSession {
        val stubClient = object : ClaudeClient("fake-key", "fake-model") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): ClaudeResponse {
                return ClaudeResponse(fakeReply(messages), fakeUsage)
            }
        }
        return ConversationSession(stubClient, systemPrompt, windowSize)
    }

    @Test
    fun `history is empty at start`() {
        val session = makeSession()
        assertTrue(session.history.isEmpty())
    }

    @Test
    fun `chat adds user and assistant messages to history`() {
        val session = makeSession(fakeReply = { "pong" })
        session.chat("ping")
        assertEquals(2, session.history.size)
        assertEquals(Message("user", "ping"), session.history[0])
        assertEquals(Message("assistant", "pong"), session.history[1])
    }

    @Test
    fun `chat sends full history to client on second turn`() {
        var capturedMessages: List<Message> = emptyList()
        val session = makeSession(fakeReply = { msgs ->
            capturedMessages = msgs
            "ok"
        })
        session.chat("first")
        session.chat("second")

        // On the second call, history already contains 2 messages from first turn
        assertEquals(3, capturedMessages.size)
        assertEquals("user", capturedMessages[0].role)
        assertEquals("first", capturedMessages[0].content)
        assertEquals("assistant", capturedMessages[1].role)
        assertEquals("user", capturedMessages[2].role)
        assertEquals("second", capturedMessages[2].content)
    }

    @Test
    fun `reset clears history`() {
        val session = makeSession(fakeReply = { "reply" })
        session.chat("hello")
        session.reset()
        assertTrue(session.history.isEmpty())
    }

    @Test
    fun `chat returns the assistant reply`() {
        val session = makeSession(fakeReply = { "hello back" })
        val result = session.chat("hello")
        assertEquals("hello back", result)
    }

    @Test
    fun `chat sends only windowSize messages when history exceeds window`() {
        var capturedMessages: List<Message> = emptyList()
        val session = makeSession(windowSize = 2, fakeReply = { msgs ->
            capturedMessages = msgs
            "ok"
        })
        session.chat("first")   // history after: [u1, a1]
        session.chat("second")  // before ask: history=[u1,a1,u2], takeLast(2)=[a1,u2]

        assertEquals(2, capturedMessages.size)
        assertEquals(Message("assistant", "ok"), capturedMessages[0])
        assertEquals(Message("user", "second"), capturedMessages[1])
    }

    @Test
    fun `full history is stored even when window limits API calls`() {
        val session = makeSession(windowSize = 2, fakeReply = { "ok" })
        session.chat("first")
        session.chat("second")

        assertEquals(4, session.history.size)
    }

    @Test
    fun `windowSize of 0 sends full history`() {
        var capturedMessages: List<Message> = emptyList()
        val session = makeSession(windowSize = 0, fakeReply = { msgs ->
            capturedMessages = msgs
            "ok"
        })
        session.chat("first")
        session.chat("second")
        session.chat("third")
        // Before third ask: history = [u1,a1,u2,a2,u3] → 5 messages

        assertEquals(5, capturedMessages.size)
    }

    @Test
    fun `lastUsage reflects tokens from most recent call`() {
        val session = makeSession(fakeUsage = TokenUsage(42, 7))
        session.chat("hello")
        assertEquals(TokenUsage(42, 7), session.lastUsage)
    }

    @Test
    fun `sessionUsage accumulates tokens across multiple calls`() {
        val session = makeSession(fakeUsage = TokenUsage(10, 5))
        session.chat("first")
        session.chat("second")
        assertEquals(TokenUsage(20, 10), session.sessionUsage)
    }

    @Test
    fun `reset clears token usage`() {
        val session = makeSession(fakeUsage = TokenUsage(10, 5))
        session.chat("hello")
        session.reset()
        assertEquals(TokenUsage(0, 0), session.lastUsage)
        assertEquals(TokenUsage(0, 0), session.sessionUsage)
    }
}
