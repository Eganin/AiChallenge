// src/test/kotlin/ConversationSessionTest.kt
package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationSessionTest {

    private fun makeClient(
        fakeReply: (List<Message>) -> String = { "reply" },
        fakeUsage: TokenUsage = TokenUsage(10, 5)
    ) = object : ClaudeClient("fake-key", "fake-model") {
        override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int) =
            ClaudeResponse(fakeReply(messages), fakeUsage)
    }

    @Test
    fun `history is empty at start`() {
        val session = ConversationSession(makeClient(), strategy = SlidingWindowStrategy(0))
        assertTrue(session.history.isEmpty())
    }

    @Test
    fun `chat returns the assistant reply`() {
        val session = ConversationSession(makeClient(fakeReply = { "pong" }), strategy = SlidingWindowStrategy(0))
        assertEquals("pong", session.chat("ping"))
    }

    @Test
    fun `chat adds user and assistant messages to history`() {
        val session = ConversationSession(makeClient(fakeReply = { "pong" }), strategy = SlidingWindowStrategy(0))
        session.chat("ping")
        assertEquals(2, session.history.size)
        assertEquals(Message("user", "ping"), session.history[0])
        assertEquals(Message("assistant", "pong"), session.history[1])
    }

    @Test
    fun `chat passes buildContextMessages result to client`() {
        var capturedMessages: List<Message> = emptyList()
        val client = makeClient(fakeReply = { msgs -> capturedMessages = msgs; "ok" })
        val session = ConversationSession(client, strategy = SlidingWindowStrategy(windowSize = 2))
        session.chat("first")
        session.chat("second")
        // SlidingWindow(2): before second ask, history=[u1,a1,u2] → takeLast(2)=[a1,u2]
        assertEquals(2, capturedMessages.size)
        assertEquals(Message("assistant", "ok"), capturedMessages[0])
        assertEquals(Message("user", "second"), capturedMessages[1])
    }

    @Test
    fun `lastUsage reflects tokens from most recent call`() {
        val session = ConversationSession(makeClient(fakeUsage = TokenUsage(42, 7)), strategy = SlidingWindowStrategy(0))
        session.chat("hello")
        assertEquals(TokenUsage(42, 7), session.lastUsage)
    }

    @Test
    fun `sessionUsage accumulates tokens across calls`() {
        val session = ConversationSession(makeClient(fakeUsage = TokenUsage(10, 5)), strategy = SlidingWindowStrategy(0))
        session.chat("first")
        session.chat("second")
        assertEquals(TokenUsage(20, 10), session.sessionUsage)
    }

    @Test
    fun `reset clears history and usage`() {
        val session = ConversationSession(makeClient(fakeUsage = TokenUsage(10, 5)), strategy = SlidingWindowStrategy(0))
        session.chat("hello")
        session.reset()
        assertTrue(session.history.isEmpty())
        assertEquals(TokenUsage(0, 0), session.lastUsage)
        assertEquals(TokenUsage(0, 0), session.sessionUsage)
    }
}
