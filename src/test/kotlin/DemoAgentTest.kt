package org.example

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoAgentTest {

    private fun makeAgent(
        replies: List<String> = List(30) { i -> "Ответ $i. Что ты думаешь об этом?" }
    ): DemoAgent {
        val idx = AtomicInteger(0)
        val stubClient = object : ClaudeClient("fake", "fake-model") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): ClaudeResponse {
                return ClaudeResponse(replies[idx.getAndIncrement() % replies.size], TokenUsage(100, 50))
            }
        }
        return DemoAgent(stubClient)
    }

    @Test
    fun `short dialog produces 6 history entries (3 user + 3 assistant)`() {
        val agent = makeAgent()
        val session = agent.runShortDemo()
        assertEquals(6, session.history.size)
        assertEquals("user", session.history[0].role)
        assertEquals("user", session.history[2].role)
        assertEquals("user", session.history[4].role)
    }

    @Test
    fun `long dialog runs exactly 15 turns`() {
        val agent = makeAgent()
        val session = agent.runLongDemo()
        assertEquals(30, session.history.size) // 15 user + 15 assistant
    }

    @Test
    fun `long dialog first message is hardcoded starter`() {
        val agent = makeAgent()
        val session = agent.runLongDemo()
        assertEquals("user", session.history[0].role)
        assert(session.history[0].content.contains("Римской империи")) {
            "First message should mention Rimskoy imperii"
        }
    }

    @Test
    fun `overflow demo catches API error without propagating`() {
        val throwingClient = object : ClaudeClient("fake", "fake-model") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): ClaudeResponse {
                error("HTTP 400: {\"error\":{\"type\":\"invalid_request_error\"," +
                      "\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}")
            }
        }
        val agent = DemoAgent(throwingClient)
        // must not throw
        agent.runOverflowDemo()
    }
}
