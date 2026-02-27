package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SummaryStrategyTest {

    private fun makeStrategy(
        tailSize: Int = 4,
        summaryEvery: Int = 2
    ): SummaryStrategy {
        val stub = object : ClaudeClient("fake", "fake") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int) =
                ClaudeResponse("summary", TokenUsage(10, 5))
        }
        return SummaryStrategy(stub, tailSize, summaryEvery)
    }

    private fun addExchange(s: SummaryStrategy, user: String, asst: String) {
        s.onUserMessage(Message("user", user))
        s.onAssistantMessage(Message("assistant", asst))
    }

    @Test
    fun `type is SUMMARY`() {
        assertEquals(StrategyType.SUMMARY, makeStrategy().type)
    }

    @Test
    fun `fullHistory contains all messages`() {
        val s = makeStrategy()
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        assertEquals(4, s.fullHistory.size)
    }

    @Test
    fun `summary triggered after threshold`() {
        val s = makeStrategy(tailSize = 4, summaryEvery = 2)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")  // triggers summary
        assertNotNull(s.summary)
        assertEquals(2, s.summarizedUpTo)
    }

    @Test
    fun `restoreState works`() {
        val s = makeStrategy()
        val msgs = listOf(Message("user", "hi"), Message("assistant", "hello"))
        s.restoreState(msgs, "old summary", 2)
        assertEquals(2, s.fullHistory.size)
        assertEquals("old summary", s.summary)
        assertEquals(2, s.summarizedUpTo)
    }

    @Test
    fun `reset clears state`() {
        val s = makeStrategy(tailSize = 4, summaryEvery = 2)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")
        s.reset()
        assertNull(s.summary)
        assertEquals(0, s.fullHistory.size)
    }
}
