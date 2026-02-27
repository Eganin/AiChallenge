package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlidingWindowStrategyTest {

    private fun addExchange(s: SlidingWindowStrategy, user: String, asst: String) {
        s.onUserMessage(Message("user", user))
        s.onAssistantMessage(Message("assistant", asst))
    }

    @Test
    fun `buildContextMessages returns all messages when history fits in window`() {
        val s = SlidingWindowStrategy(windowSize = 10)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        assertEquals(4, s.buildContextMessages().size)
    }

    @Test
    fun `buildContextMessages returns only last windowSize messages`() {
        val s = SlidingWindowStrategy(windowSize = 2)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")
        val ctx = s.buildContextMessages()
        assertEquals(2, ctx.size)
        assertEquals(Message("user", "u3"), ctx[0])
        assertEquals(Message("assistant", "a3"), ctx[1])
    }

    @Test
    fun `windowSize 0 sends all messages`() {
        val s = SlidingWindowStrategy(windowSize = 0)
        repeat(10) { i -> addExchange(s, "u$i", "a$i") }
        assertEquals(20, s.buildContextMessages().size)
    }

    @Test
    fun `fullHistory always contains all messages`() {
        val s = SlidingWindowStrategy(windowSize = 2)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")
        assertEquals(6, s.fullHistory.size)
    }

    @Test
    fun `reset clears history`() {
        val s = SlidingWindowStrategy(windowSize = 10)
        addExchange(s, "u1", "a1")
        s.reset()
        assertTrue(s.fullHistory.isEmpty())
        assertTrue(s.buildContextMessages().isEmpty())
    }

    @Test
    fun `restoreState loads messages`() {
        val s = SlidingWindowStrategy(windowSize = 10)
        val msgs = listOf(Message("user", "hi"), Message("assistant", "hello"))
        s.restoreState(msgs)
        assertEquals(2, s.fullHistory.size)
    }

    @Test
    fun `getStrategyInfo reports correct counts`() {
        val s = SlidingWindowStrategy(windowSize = 2)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")
        val info = s.getStrategyInfo()
        assertEquals(StrategyType.SLIDING_WINDOW, info.type)
        assertEquals(6, info.totalMessages)
        assertEquals(2, info.sentMessages)
    }
}
