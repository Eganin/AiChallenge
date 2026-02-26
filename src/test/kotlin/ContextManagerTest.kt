package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextManagerTest {

    private fun makeManager(
        tailSize: Int = 4,
        summaryEvery: Int = 2,
        replyFn: (List<Message>) -> String = { "summary" }
    ): ContextManager {
        val stubClient = object : ClaudeClient("fake-key", "fake-model") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): ClaudeResponse {
                return ClaudeResponse(replyFn(messages), TokenUsage(10, 5))
            }
        }
        return ContextManager(stubClient, tailSize, summaryEvery)
    }

    private fun addExchange(mgr: ContextManager, user: String, assistant: String) {
        mgr.addMessage(Message("user", user))
        mgr.addMessage(Message("assistant", assistant))
    }

    // tailSize=4, summaryEvery=2: after 4 exchanges (8 msgs) → old=4 ≥ summaryEvery=2 → trigger
    // Let's use 2 exchanges to stay within tail and 1 extra to push old messages out
    @Test
    fun `no summary when all messages fit in tail`() {
        val mgr = makeManager(tailSize = 10, summaryEvery = 4)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        // 4 messages, tailSize=10 → old=0 → no summary
        assertNull(mgr.summary)
        assertEquals(0, mgr.summarizedUpTo)
    }

    @Test
    fun `summary triggered when old messages reach summaryEvery`() {
        // tailSize=4, summaryEvery=2: after 3 exchanges (6 msgs), old=6-4=2 ≥ 2 → trigger
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")

        assertNotNull(mgr.summary)
        assertEquals("summary", mgr.summary)
        assertEquals(2, mgr.summarizedUpTo)
    }

    @Test
    fun `no summary triggered when within summaryEvery threshold`() {
        // tailSize=4, summaryEvery=4: after 3 exchanges (6 msgs), old=2 < 4 → no trigger
        val mgr = makeManager(tailSize = 4, summaryEvery = 4)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")

        assertNull(mgr.summary)
    }

    @Test
    fun `context messages include summary placeholder when summary exists`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")  // triggers summary of [u1,a1]

        val ctx = mgr.buildContextMessages()
        // summary placeholder (2) + last 4 messages [u2,a2,u3,a3] = 6
        assertEquals(6, ctx.size)
        assertEquals("user", ctx[0].role)
        assertTrue(ctx[0].content.contains("summary"))
        assertEquals("assistant", ctx[1].role)
        assertEquals(Message("user", "u2"), ctx[2])
        assertEquals(Message("assistant", "a2"), ctx[3])
        assertEquals(Message("user", "u3"), ctx[4])
        assertEquals(Message("assistant", "a3"), ctx[5])
    }

    @Test
    fun `context messages have no summary placeholder when no summary`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")

        val ctx = mgr.buildContextMessages()
        assertEquals(2, ctx.size)
        assertEquals(Message("user", "u1"), ctx[0])
        assertEquals(Message("assistant", "a1"), ctx[1])
    }

    @Test
    fun `no summary when summaryEvery is zero`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 0)
        repeat(10) { i -> addExchange(mgr, "u$i", "a$i") }
        assertNull(mgr.summary)
    }

    @Test
    fun `no summary when tailSize is zero`() {
        val mgr = makeManager(tailSize = 0, summaryEvery = 2)
        repeat(10) { i -> addExchange(mgr, "u$i", "a$i") }
        assertNull(mgr.summary)
    }

    @Test
    fun `second summarization builds incrementally on first`() {
        var callCount = 0
        val mgr = makeManager(tailSize = 4, summaryEvery = 2, replyFn = { "summary${++callCount}" })

        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")  // 1st summary call: summary1, summarizedUpTo=2
        addExchange(mgr, "u4", "a4")  // old=4, unsummarized=4-2=2 ≥ 2 → 2nd call: summary2, summarizedUpTo=4
        addExchange(mgr, "u5", "a5")  // old=6, unsummarized=6-4=2 ≥ 2 → 3rd call: summary3, summarizedUpTo=6

        assertEquals("summary3", mgr.summary)
        assertEquals(6, mgr.summarizedUpTo)
        assertEquals(3, callCount)
    }

    @Test
    fun `restoreState loads previous state correctly`() {
        val mgr = makeManager()
        val msgs = listOf(Message("user", "hi"), Message("assistant", "hello"))
        mgr.restoreState(msgs, "old summary", 2)

        assertEquals(2, mgr.fullHistory.size)
        assertEquals("old summary", mgr.summary)
        assertEquals(2, mgr.summarizedUpTo)
    }

    @Test
    fun `restoreState with null summary clears existing summary`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")  // triggers summary

        mgr.restoreState(emptyList(), null, 0)
        assertNull(mgr.summary)
        assertEquals(0, mgr.summarizedUpTo)
    }

    @Test
    fun `reset clears all state`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")

        mgr.reset()
        assertTrue(mgr.fullHistory.isEmpty())
        assertNull(mgr.summary)
        assertEquals(0, mgr.summarizedUpTo)
        assertEquals(TokenUsage(0, 0), mgr.summaryUsage)
    }

    @Test
    fun `summaryUsage accumulates from summarization API calls`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")  // triggers 1 summary call: TokenUsage(10,5)

        assertEquals(TokenUsage(10, 5), mgr.summaryUsage)
    }

    @Test
    fun `summaryUsage accumulates across multiple summarizations`() {
        val mgr = makeManager(tailSize = 4, summaryEvery = 2)
        // exchange 3 → 1st summary call (old=2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")
        // exchange 4 → 2nd summary call (old=4)
        addExchange(mgr, "u4", "a4")
        // exchange 5 → 3rd summary call (old=6)
        addExchange(mgr, "u5", "a5")

        // 3 summary calls × TokenUsage(10,5)
        assertEquals(TokenUsage(30, 15), mgr.summaryUsage)
    }

    @Test
    fun `full history always contains all messages`() {
        val mgr = makeManager(tailSize = 2, summaryEvery = 2)
        addExchange(mgr, "u1", "a1")
        addExchange(mgr, "u2", "a2")
        addExchange(mgr, "u3", "a3")

        assertEquals(6, mgr.fullHistory.size)
    }
}
