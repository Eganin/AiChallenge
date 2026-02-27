package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickyFactsStrategyTest {

    private fun makeStrategy(
        tailSize: Int = 10,
        factsResponse: String = """{"цель":"test"}"""
    ): StickyFactsStrategy {
        val stub = object : ClaudeClient("fake", "fake") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): ClaudeResponse =
                ClaudeResponse(factsResponse, TokenUsage(10, 5))
        }
        return StickyFactsStrategy(stub, tailSize)
    }

    private fun addExchange(s: StickyFactsStrategy, user: String, asst: String) {
        s.onUserMessage(Message("user", user))
        s.onAssistantMessage(Message("assistant", asst))
    }

    @Test
    fun `facts empty at start`() {
        val s = makeStrategy()
        assertTrue(s.facts.isEmpty())
    }

    @Test
    fun `facts updated after assistant message`() {
        val s = makeStrategy(factsResponse = """{"цель":"написать агент"}""")
        addExchange(s, "хочу написать агент", "хорошо!")
        assertEquals("написать агент", s.facts["цель"])
    }

    @Test
    fun `buildContextMessages with no facts returns tail only`() {
        val stub = object : ClaudeClient("fake", "fake") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int) =
                ClaudeResponse("{}", TokenUsage(5, 2))
        }
        val s = StickyFactsStrategy(stub, tailSize = 10)
        s.onUserMessage(Message("user", "hi"))
        s.onAssistantMessage(Message("assistant", "hello"))
        // facts is {} → empty after parse
        val ctx = s.buildContextMessages()
        assertEquals(2, ctx.size)
        assertEquals(Message("user", "hi"), ctx[0])
    }

    @Test
    fun `buildContextMessages with facts prepends facts placeholder`() {
        val s = makeStrategy(factsResponse = """{"ключ":"значение"}""")
        addExchange(s, "u1", "a1")
        val ctx = s.buildContextMessages()
        // [facts-user, facts-asst, u1, a1]
        assertEquals(4, ctx.size)
        assertEquals("user", ctx[0].role)
        assertTrue(ctx[0].content.contains("ключ"))
        assertTrue(ctx[0].content.contains("значение"))
        assertEquals("assistant", ctx[1].role)
    }

    @Test
    fun `buildContextMessages tail limits messages sent`() {
        val s = makeStrategy(tailSize = 2, factsResponse = """{"k":"v"}""")
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")
        val ctx = s.buildContextMessages()
        // history = [u1, a1, u2, a2, u3, a3]
        // takeLast(2) = [u3, a3]
        // [facts-user, facts-asst] + [u3, a3] = 4
        assertEquals(4, ctx.size)
        assertEquals(Message("user", "u3"), ctx[2])
        assertEquals(Message("assistant", "a3"), ctx[3])
    }

    @Test
    fun `fullHistory stores all messages`() {
        val s = makeStrategy(tailSize = 2)
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        addExchange(s, "u3", "a3")
        assertEquals(6, s.fullHistory.size)
    }

    @Test
    fun `reset clears history and facts`() {
        val s = makeStrategy(factsResponse = """{"k":"v"}""")
        addExchange(s, "u1", "a1")
        s.reset()
        assertTrue(s.facts.isEmpty())
        assertTrue(s.fullHistory.isEmpty())
    }

    @Test
    fun `removeFact removes a key`() {
        val s = makeStrategy(factsResponse = """{"k":"v"}""")
        addExchange(s, "u1", "a1")
        s.removeFact("k")
        assertTrue(s.facts.isEmpty())
    }

    @Test
    fun `restoreState loads messages and facts`() {
        val s = makeStrategy()
        val msgs = listOf(Message("user", "hi"), Message("assistant", "hello"))
        s.restoreState(msgs, """{"goal":"test"}""")
        assertEquals(2, s.fullHistory.size)
        assertEquals("test", s.facts["goal"])
    }

    @Test
    fun `factsUsage accumulates from LLM calls`() {
        val s = makeStrategy(factsResponse = """{"k":"v"}""")
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        assertEquals(TokenUsage(20, 10), s.factsUsage)
    }
}
