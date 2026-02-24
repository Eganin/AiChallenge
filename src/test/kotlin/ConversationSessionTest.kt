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
        fakeReply: (List<Message>) -> String = { "reply" }
    ): ConversationSession {
        val stubClient = object : ClaudeClient("fake-key", "fake-model") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): String {
                return fakeReply(messages)
            }
        }
        return ConversationSession(stubClient, systemPrompt)
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
}
