package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class BranchingStrategyTest {

    private fun addExchange(s: BranchingStrategy, user: String, asst: String) {
        s.onUserMessage(Message("user", user))
        s.onAssistantMessage(Message("assistant", asst))
    }

    @Test
    fun `starts on trunk with no branches`() {
        val s = BranchingStrategy()
        assertTrue(s.isOnTrunk)
        assertTrue(s.branches.isEmpty())
    }

    @Test
    fun `messages on trunk go to trunkMessages`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        assertEquals(2, s.trunkMessages.size)
        assertEquals(2, s.fullHistory.size)
    }

    @Test
    fun `createBranch marks checkpoint and switches to branch`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        val branch = s.createBranch("alt")
        assertEquals(4, branch.checkpointPosition)
        assertFalse(s.isOnTrunk)
        assertEquals("alt", s.activeBranch?.name)
    }

    @Test
    fun `messages after branch go to branch not trunk`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        s.createBranch("alt")
        addExchange(s, "u2", "a2")
        assertEquals(2, s.trunkMessages.size)
        assertEquals(2, s.activeBranch!!.messages.size)
    }

    @Test
    fun `buildContextMessages on trunk returns trunk messages`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        val ctx = s.buildContextMessages()
        assertEquals(2, ctx.size)
        assertEquals(Message("user", "u1"), ctx[0])
    }

    @Test
    fun `buildContextMessages on branch returns trunk up to checkpoint plus branch messages`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        addExchange(s, "u2", "a2")
        s.createBranch("alt")
        addExchange(s, "u3", "a3")
        val ctx = s.buildContextMessages()
        assertEquals(6, ctx.size)
        assertEquals(Message("user", "u1"), ctx[0])
        assertEquals(Message("assistant", "a2"), ctx[3])
        assertEquals(Message("user", "u3"), ctx[4])
        assertEquals(Message("assistant", "a3"), ctx[5])
    }

    @Test
    fun `two branches share trunk, diverge after checkpoint`() {
        val s = BranchingStrategy()
        addExchange(s, "trunk1", "trunk-a1")
        s.createBranch("branch-A")
        addExchange(s, "A1", "A-a1")

        // create second branch also from same trunk
        s.switchTo("trunk")
        s.createBranch("branch-B")
        addExchange(s, "B1", "B-a1")

        s.switchTo("branch-A")
        val ctxA = s.buildContextMessages()
        assertEquals(4, ctxA.size)
        assertEquals(Message("user", "A1"), ctxA[2])

        s.switchTo("branch-B")
        val ctxB = s.buildContextMessages()
        assertEquals(4, ctxB.size)
        assertEquals(Message("user", "B1"), ctxB[2])
    }

    @Test
    fun `switchTo unknown branch returns false`() {
        val s = BranchingStrategy()
        assertFalse(s.switchTo("nonexistent"))
    }

    @Test
    fun `switchTo trunk switches back`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        s.createBranch("alt")
        s.switchTo("trunk")
        assertTrue(s.isOnTrunk)
        assertNull(s.activeBranch)
    }

    @Test
    fun `createBranch fails if name already exists`() {
        val s = BranchingStrategy()
        s.createBranch("alt")
        assertFailsWith<IllegalArgumentException> { s.createBranch("alt") }
    }

    @Test
    fun `createBranch fails for reserved name trunk`() {
        val s = BranchingStrategy()
        assertFailsWith<IllegalArgumentException> { s.createBranch("trunk") }
    }

    @Test
    fun `reset clears everything`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        s.createBranch("alt")
        s.reset()
        assertTrue(s.trunkMessages.isEmpty())
        assertTrue(s.branches.isEmpty())
        assertTrue(s.isOnTrunk)
    }

    @Test
    fun `fullHistory on trunk is trunk messages`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        assertEquals(s.trunkMessages, s.fullHistory)
    }

    @Test
    fun `fullHistory on branch combines trunk up to checkpoint plus branch`() {
        val s = BranchingStrategy()
        addExchange(s, "u1", "a1")
        s.createBranch("alt")
        addExchange(s, "b1", "b-a1")
        val history = s.fullHistory
        assertEquals(4, history.size)
        assertEquals(Message("user", "u1"), history[0])
        assertEquals(Message("user", "b1"), history[2])
    }

    @Test
    fun `restoreState loads trunk and branches`() {
        val s = BranchingStrategy()
        val trunk = listOf(Message("user", "t1"), Message("assistant", "t-a1"))
        val branchRec = BranchRecord(id = 1L, name = "alt", checkpointPosition = 2,
            messages = mutableListOf(Message("user", "b1"), Message("assistant", "b-a1")))
        s.restoreState(trunk, listOf(branchRec), activeBranchId = 1L)
        assertFalse(s.isOnTrunk)
        assertEquals("alt", s.activeBranch?.name)
        assertEquals(4, s.fullHistory.size)
    }
}
