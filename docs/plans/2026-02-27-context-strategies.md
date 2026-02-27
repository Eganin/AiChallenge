# Context Management Strategies Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add three pluggable context management strategies (Sliding Window, Sticky Facts, Branching) with a `ContextStrategy` interface, replace hard-wired `ContextManager` in `ConversationSession`, and wire strategy selection into `CliAgent`.

**Architecture:** `ContextStrategy` interface with 4 implementations (+ existing logic wrapped as `SummaryStrategy`). `ConversationSession` accepts a `ContextStrategy`. `CliAgent` selects strategy at session creation (stored in DB). Branching persists branches to a new `branches` table; facts stored as JSON in `sessions.facts`.

**Tech Stack:** Kotlin/JVM 21, SQLite (xerial), org.json (for facts JSON), kotlin.test/JUnit5

---

## How to run tests

```bash
./gradlew test                          # all tests
./gradlew test --tests "org.example.*StrategyTest"   # just strategy tests
./gradlew test --tests "org.example.ConversationSessionTest"
./gradlew test --tests "org.example.SessionRepositoryTest"
```

---

### Task 1: ContextStrategy Interface

**Files:**
- Create: `src/main/kotlin/ContextStrategy.kt`

**Step 1: Write the file**

```kotlin
package org.example

enum class StrategyType { SUMMARY, SLIDING_WINDOW, STICKY_FACTS, BRANCHING }

data class StrategyInfo(
    val type: StrategyType,
    val totalMessages: Int,
    val sentMessages: Int,
    val extra: Map<String, String> = emptyMap()
)

interface ContextStrategy {
    val type: StrategyType
    val fullHistory: List<Message>
    fun onUserMessage(msg: Message)
    fun onAssistantMessage(msg: Message)
    fun buildContextMessages(): List<Message>
    fun reset()
    fun getStrategyInfo(): StrategyInfo
}
```

**Step 2: Compile check**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/ContextStrategy.kt
git commit -m "feat: add ContextStrategy interface, StrategyType enum, StrategyInfo"
```

---

### Task 2: SlidingWindowStrategy

**Files:**
- Create: `src/main/kotlin/SlidingWindowStrategy.kt`
- Create: `src/test/kotlin/SlidingWindowStrategyTest.kt`

**Step 1: Write failing tests**

```kotlin
// src/test/kotlin/SlidingWindowStrategyTest.kt
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
        assertEquals(Message("assistant", "a2"), ctx[0])
        assertEquals(Message("user", "u3"), ctx[1])
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
```

**Step 2: Run test to confirm failure**

```bash
./gradlew test --tests "org.example.SlidingWindowStrategyTest"
```
Expected: FAIL — `SlidingWindowStrategy` not found

**Step 3: Implement**

```kotlin
// src/main/kotlin/SlidingWindowStrategy.kt
package org.example

class SlidingWindowStrategy(val windowSize: Int = 10) : ContextStrategy {
    private val _history = mutableListOf<Message>()

    override val type = StrategyType.SLIDING_WINDOW
    override val fullHistory: List<Message> get() = _history.toList()

    override fun onUserMessage(msg: Message) { _history.add(msg) }
    override fun onAssistantMessage(msg: Message) { _history.add(msg) }

    override fun buildContextMessages(): List<Message> =
        if (windowSize > 0) _history.takeLast(windowSize) else _history.toList()

    override fun reset() { _history.clear() }

    override fun getStrategyInfo() = StrategyInfo(
        type = StrategyType.SLIDING_WINDOW,
        totalMessages = _history.size,
        sentMessages = if (windowSize > 0) minOf(windowSize, _history.size) else _history.size
    )

    fun restoreState(messages: List<Message>) {
        _history.clear()
        _history.addAll(messages)
    }
}
```

**Step 4: Run tests**

```bash
./gradlew test --tests "org.example.SlidingWindowStrategyTest"
```
Expected: all 7 tests PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/SlidingWindowStrategy.kt src/test/kotlin/SlidingWindowStrategyTest.kt
git commit -m "feat: add SlidingWindowStrategy with tests"
```

---

### Task 3: StickyFactsStrategy

**Files:**
- Create: `src/main/kotlin/StickyFactsStrategy.kt`
- Create: `src/test/kotlin/StickyFactsStrategyTest.kt`

**Step 1: Write failing tests**

```kotlin
// src/test/kotlin/StickyFactsStrategyTest.kt
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
        // [facts-user, facts-asst] + last 2 messages = 4
        assertEquals(4, ctx.size)
        assertEquals(Message("assistant", "a2"), ctx[2])
        assertEquals(Message("user", "u3"), ctx[3])
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
```

**Step 2: Run test to confirm failure**

```bash
./gradlew test --tests "org.example.StickyFactsStrategyTest"
```
Expected: FAIL

**Step 3: Implement**

```kotlin
// src/main/kotlin/StickyFactsStrategy.kt
package org.example

import org.json.JSONObject

class StickyFactsStrategy(
    private val client: ClaudeClient,
    val tailSize: Int = 10
) : ContextStrategy {

    private val _history = mutableListOf<Message>()
    val facts = mutableMapOf<String, String>()

    override val type = StrategyType.STICKY_FACTS
    override val fullHistory: List<Message> get() = _history.toList()

    var factsUsage = TokenUsage(0, 0)
        private set

    override fun onUserMessage(msg: Message) {
        _history.add(msg)
    }

    override fun onAssistantMessage(msg: Message) {
        _history.add(msg)
        extractFacts()
    }

    private fun extractFacts() {
        val lastTwo = _history.takeLast(2)
        val existingFacts = if (facts.isEmpty()) "" else
            "Текущие факты:\n" + facts.entries.joinToString("\n") { (k, v) -> "- $k: $v" } + "\n\n"
        val exchange = lastTwo.joinToString("\n") { msg ->
            val role = if (msg.role == "user") "Пользователь" else "Ассистент"
            "$role: ${msg.content}"
        }
        val prompt = "${existingFacts}Последний обмен:\n$exchange\n\n" +
            "Обнови список ключевых фактов (цель, предпочтения, решения, ограничения). " +
            "Верни ТОЛЬКО JSON объект без комментариев: {\"ключ\": \"значение\", ...}. " +
            "Если новых фактов нет, верни пустой объект {}."
        val response = client.ask(
            messages = listOf(Message("user", prompt)),
            systemPrompt = "Ты извлекаешь ключевые факты из диалогов. Отвечай только JSON.",
            maxTokens = 256
        )
        parseFacts(response.text).forEach { (k, v) -> facts[k] = v }
        factsUsage = TokenUsage(
            factsUsage.inputTokens + response.usage.inputTokens,
            factsUsage.outputTokens + response.usage.outputTokens
        )
    }

    private fun parseFacts(json: String): Map<String, String> {
        return try {
            val trimmed = json.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end < 0) return emptyMap()
            val jsonStr = trimmed.substring(start, end + 1)
            val obj = JSONObject(jsonStr)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun buildContextMessages(): List<Message> {
        val tail = if (tailSize > 0) _history.takeLast(tailSize) else _history.toList()
        val result = mutableListOf<Message>()
        if (facts.isNotEmpty()) {
            val factsText = facts.entries.joinToString("\n") { (k, v) -> "- $k: $v" }
            result.add(Message("user", "[Ключевые факты разговора]\n$factsText"))
            result.add(Message("assistant", "Понял, учту ключевые факты."))
        }
        result.addAll(tail)
        return result
    }

    override fun reset() {
        _history.clear()
        facts.clear()
        factsUsage = TokenUsage(0, 0)
    }

    override fun getStrategyInfo() = StrategyInfo(
        type = StrategyType.STICKY_FACTS,
        totalMessages = _history.size,
        sentMessages = if (tailSize > 0) minOf(tailSize, _history.size) else _history.size,
        extra = mapOf("факты" to facts.size.toString())
    )

    fun removeFact(key: String) { facts.remove(key) }

    val factsJson: String
        get() = if (facts.isEmpty()) "{}" else
            JSONObject(facts as Map<*, *>).toString()

    fun restoreState(messages: List<Message>, factsJson: String?) {
        _history.clear()
        _history.addAll(messages)
        facts.clear()
        if (!factsJson.isNullOrBlank()) {
            parseFacts(factsJson).forEach { (k, v) -> facts[k] = v }
        }
    }
}
```

**Step 4: Run tests**

```bash
./gradlew test --tests "org.example.StickyFactsStrategyTest"
```
Expected: all 10 tests PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/StickyFactsStrategy.kt src/test/kotlin/StickyFactsStrategyTest.kt
git commit -m "feat: add StickyFactsStrategy with automatic facts extraction"
```

---

### Task 4: BranchingStrategy

**Files:**
- Create: `src/main/kotlin/BranchingStrategy.kt`
- Create: `src/test/kotlin/BranchingStrategyTest.kt`

**Step 1: Write failing tests**

```kotlin
// src/test/kotlin/BranchingStrategyTest.kt
package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
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
```

**Step 2: Run test to confirm failure**

```bash
./gradlew test --tests "org.example.BranchingStrategyTest"
```
Expected: FAIL

**Step 3: Implement**

```kotlin
// src/main/kotlin/BranchingStrategy.kt
package org.example

class BranchRecord(
    var id: Long,
    val name: String,
    val checkpointPosition: Int,
    val messages: MutableList<Message> = mutableListOf(),
    var savedMessageCount: Int = 0
)

class BranchingStrategy : ContextStrategy {
    private val _trunkMessages = mutableListOf<Message>()
    private val _branches = mutableListOf<BranchRecord>()
    private var _activeBranchIndex: Int = -1

    override val type = StrategyType.BRANCHING

    val trunkMessages: List<Message> get() = _trunkMessages.toList()
    val branches: List<BranchRecord> get() = _branches.toList()
    val activeBranch: BranchRecord? get() = if (_activeBranchIndex >= 0) _branches[_activeBranchIndex] else null
    val isOnTrunk: Boolean get() = _activeBranchIndex < 0
    var trunkSavedCount: Int = 0

    override val fullHistory: List<Message>
        get() {
            val ab = activeBranch
            return if (ab != null) _trunkMessages.take(ab.checkpointPosition) + ab.messages
            else _trunkMessages.toList()
        }

    override fun onUserMessage(msg: Message) {
        activeBranch?.messages?.add(msg) ?: _trunkMessages.add(msg)
    }

    override fun onAssistantMessage(msg: Message) {
        activeBranch?.messages?.add(msg) ?: _trunkMessages.add(msg)
    }

    override fun buildContextMessages(): List<Message> = fullHistory.toList()

    override fun reset() {
        _trunkMessages.clear()
        _branches.clear()
        _activeBranchIndex = -1
        trunkSavedCount = 0
    }

    override fun getStrategyInfo() = StrategyInfo(
        type = StrategyType.BRANCHING,
        totalMessages = fullHistory.size,
        sentMessages = fullHistory.size,
        extra = mapOf(
            "ветка" to (activeBranch?.name ?: "trunk"),
            "веток" to _branches.size.toString()
        )
    )

    fun createBranch(name: String): BranchRecord {
        require(_branches.none { it.name == name }) { "Ветка «$name» уже существует" }
        val checkpoint = _trunkMessages.size
        val branch = BranchRecord(id = -1L, name = name, checkpointPosition = checkpoint)
        _branches.add(branch)
        _activeBranchIndex = _branches.size - 1
        return branch
    }

    fun switchTo(name: String): Boolean {
        if (name == "trunk") { _activeBranchIndex = -1; return true }
        val idx = _branches.indexOfFirst { it.name == name }
        if (idx < 0) return false
        _activeBranchIndex = idx
        return true
    }

    fun updateBranchId(name: String, id: Long) {
        _branches.firstOrNull { it.name == name }?.id = id
    }

    fun restoreState(trunkMessages: List<Message>, branches: List<BranchRecord>, activeBranchId: Long?) {
        _trunkMessages.clear()
        _trunkMessages.addAll(trunkMessages)
        _branches.clear()
        _branches.addAll(branches)
        trunkSavedCount = trunkMessages.size
        _activeBranchIndex = if (activeBranchId != null)
            _branches.indexOfFirst { it.id == activeBranchId }
        else -1
    }
}
```

**Step 4: Run tests**

```bash
./gradlew test --tests "org.example.BranchingStrategyTest"
```
Expected: all 15 tests PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/BranchingStrategy.kt src/test/kotlin/BranchingStrategyTest.kt
git commit -m "feat: add BranchingStrategy with checkpoint and branch switching"
```

---

### Task 5: SummaryStrategy (wrap ContextManager)

**Files:**
- Create: `src/main/kotlin/SummaryStrategy.kt`
- Create: `src/test/kotlin/SummaryStrategyTest.kt`

**Step 1: Write failing tests**

```kotlin
// src/test/kotlin/SummaryStrategyTest.kt
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
```

**Step 2: Confirm failure**

```bash
./gradlew test --tests "org.example.SummaryStrategyTest"
```

**Step 3: Implement**

```kotlin
// src/main/kotlin/SummaryStrategy.kt
package org.example

class SummaryStrategy(
    client: ClaudeClient,
    tailSize: Int = 10,
    summaryEvery: Int = 10
) : ContextStrategy {

    val contextManager = ContextManager(client, tailSize, summaryEvery)

    override val type = StrategyType.SUMMARY
    override val fullHistory: List<Message> get() = contextManager.fullHistory

    val summary: String? get() = contextManager.summary
    val summarizedUpTo: Int get() = contextManager.summarizedUpTo
    val summaryUsage: TokenUsage get() = contextManager.summaryUsage
    val tailSize: Int get() = contextManager.tailSize

    override fun onUserMessage(msg: Message) = contextManager.addMessage(msg)
    override fun onAssistantMessage(msg: Message) = contextManager.addMessage(msg)
    override fun buildContextMessages() = contextManager.buildContextMessages()
    override fun reset() = contextManager.reset()

    override fun getStrategyInfo(): StrategyInfo {
        val sentCount = if (contextManager.tailSize > 0)
            minOf(contextManager.tailSize, contextManager.fullHistory.size)
        else contextManager.fullHistory.size
        return StrategyInfo(
            type = StrategyType.SUMMARY,
            totalMessages = contextManager.fullHistory.size,
            sentMessages = sentCount,
            extra = buildMap {
                if (contextManager.summary != null)
                    put("summary", "${contextManager.summarizedUpTo} сообщ. сжато")
                if (contextManager.summaryUsage.inputTokens > 0)
                    put("summaryTokens", "${contextManager.summaryUsage.inputTokens}/${contextManager.summaryUsage.outputTokens}")
            }
        )
    }

    fun restoreState(messages: List<Message>, summary: String?, summarizedUpTo: Int) =
        contextManager.restoreState(messages, summary, summarizedUpTo)
}
```

**Step 4: Run tests**

```bash
./gradlew test --tests "org.example.SummaryStrategyTest"
./gradlew test --tests "org.example.ContextManagerTest"   # must still pass
```
Expected: all PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/SummaryStrategy.kt src/test/kotlin/SummaryStrategyTest.kt
git commit -m "feat: add SummaryStrategy wrapping ContextManager"
```

---

### Task 6: Refactor ConversationSession + update tests

**Files:**
- Modify: `src/main/kotlin/ConversationSession.kt`
- Modify: `src/test/kotlin/ConversationSessionTest.kt`
- Modify: `src/main/kotlin/DemoAgent.kt`

**Step 1: Rewrite ConversationSession**

Replace the file entirely:

```kotlin
// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    val strategy: ContextStrategy
) {
    val history: List<Message> get() = strategy.fullHistory

    var lastUsage = TokenUsage(0, 0)
        private set

    var sessionUsage = TokenUsage(0, 0)
        private set

    fun chat(userMessage: String): String {
        strategy.onUserMessage(Message("user", userMessage))
        val contextMessages = strategy.buildContextMessages()
        val response = client.ask(contextMessages, systemPrompt)
        strategy.onAssistantMessage(Message("assistant", response.text))
        lastUsage = response.usage
        sessionUsage = TokenUsage(
            inputTokens = sessionUsage.inputTokens + response.usage.inputTokens,
            outputTokens = sessionUsage.outputTokens + response.usage.outputTokens
        )
        return response.text
    }

    fun reset() {
        strategy.reset()
        lastUsage = TokenUsage(0, 0)
        sessionUsage = TokenUsage(0, 0)
    }
}
```

**Step 2: Update DemoAgent to use SlidingWindowStrategy**

Replace all `ConversationSession(client, tailSize = 0, summaryEvery = 0)` with:
```kotlin
ConversationSession(client, strategy = SlidingWindowStrategy(windowSize = 0))
```
And `ConversationSession(client, systemPrompt = "...", tailSize = 0, summaryEvery = 0)` with:
```kotlin
ConversationSession(client, systemPrompt = "...", strategy = SlidingWindowStrategy(windowSize = 0))
```

**Step 3: Rewrite ConversationSessionTest**

```kotlin
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
```

**Step 4: Run all tests**

```bash
./gradlew test
```
Expected: all tests PASS (ContextManagerTest, ConversationSessionTest, DemoAgentTest, etc.)

**Step 5: Commit**

```bash
git add src/main/kotlin/ConversationSession.kt src/main/kotlin/DemoAgent.kt src/test/kotlin/ConversationSessionTest.kt
git commit -m "refactor: ConversationSession accepts ContextStrategy, update DemoAgent"
```

---

### Task 7: DB Schema + SessionRepository

**Files:**
- Modify: `src/main/kotlin/SessionRepository.kt`
- Modify: `src/test/kotlin/SessionRepositoryTest.kt`

**Step 1: Read existing SessionRepositoryTest** to understand what tests exist already before modifying.

**Step 2: Update SessionRepository**

Add the following to the `init` block's migrations (append after existing migrations):

```kotlin
// New migrations for strategies
listOf(
    "ALTER TABLE sessions ADD COLUMN strategy TEXT NOT NULL DEFAULT 'SUMMARY'",
    "ALTER TABLE sessions ADD COLUMN facts TEXT",
    "ALTER TABLE sessions ADD COLUMN active_branch_id INTEGER",
    "ALTER TABLE messages ADD COLUMN branch_id INTEGER",
    """CREATE TABLE IF NOT EXISTS branches (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        name       TEXT NOT NULL,
        checkpoint INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL
    )"""
).forEach { ddl ->
    try { connection.createStatement().use { it.executeUpdate(ddl) } } catch (_: Exception) {}
}
```

Update `createSession` to accept strategy type:

```kotlin
fun createSession(name: String, systemPrompt: String?, strategy: StrategyType = StrategyType.SUMMARY): SessionRecord {
    connection.prepareStatement(
        "INSERT INTO sessions (name, system_prompt, created_at, strategy) VALUES (?, ?, ?, ?)"
    ).use { stmt ->
        stmt.setString(1, name)
        stmt.setString(2, systemPrompt)
        stmt.setString(3, Instant.now().toString())
        stmt.setString(4, strategy.name)
        stmt.executeUpdate()
    }
    return connection.prepareStatement("SELECT id FROM sessions WHERE name = ?").use { stmt ->
        stmt.setString(1, name)
        stmt.executeQuery().use { rs ->
            rs.next()
            SessionRecord(rs.getLong("id"), name, systemPrompt, strategy)
        }
    }
}
```

Update `SessionRecord` data class (in SessionRepository.kt):

```kotlin
data class SessionRecord(
    val id: Long,
    val name: String,
    val systemPrompt: String?,
    val strategy: StrategyType = StrategyType.SUMMARY
)
```

Update `listSessions` to read strategy:

```kotlin
fun listSessions(): List<SessionRecord> {
    val results = mutableListOf<SessionRecord>()
    connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT id, name, system_prompt, strategy FROM sessions ORDER BY created_at").use { rs ->
            while (rs.next()) {
                val strategyType = try {
                    StrategyType.valueOf(rs.getString("strategy") ?: "SUMMARY")
                } catch (_: Exception) { StrategyType.SUMMARY }
                results += SessionRecord(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    systemPrompt = rs.getString("system_prompt"),
                    strategy = strategyType
                )
            }
        }
    }
    return results
}
```

Update `loadMessages` to filter by `branchId`:

```kotlin
fun loadMessages(sessionId: Long, branchId: Long? = null): List<Message> {
    val sql = if (branchId == null)
        "SELECT role, content FROM messages WHERE session_id = ? AND branch_id IS NULL ORDER BY position"
    else
        "SELECT role, content FROM messages WHERE session_id = ? AND branch_id = ? ORDER BY position"
    val results = mutableListOf<Message>()
    connection.prepareStatement(sql).use { stmt ->
        stmt.setLong(1, sessionId)
        if (branchId != null) stmt.setLong(2, branchId)
        stmt.executeQuery().use { rs ->
            while (rs.next()) results += Message(rs.getString("role"), rs.getString("content"))
        }
    }
    return results
}
```

Update `appendMessages` to accept optional `branchId`:

```kotlin
fun appendMessages(sessionId: Long, messages: List<Message>, startPosition: Int, branchId: Long? = null) {
    connection.prepareStatement(
        "INSERT INTO messages (session_id, role, content, position, branch_id) VALUES (?, ?, ?, ?, ?)"
    ).use { stmt ->
        messages.forEachIndexed { index, msg ->
            stmt.setLong(1, sessionId)
            stmt.setString(2, msg.role)
            stmt.setString(3, msg.content)
            stmt.setInt(4, startPosition + index)
            if (branchId != null) stmt.setLong(5, branchId)
            else stmt.setNull(5, java.sql.Types.INTEGER)
            stmt.addBatch()
        }
        stmt.executeBatch()
    }
}
```

Add new methods after existing ones:

```kotlin
fun updateFacts(sessionId: Long, factsJson: String?) {
    connection.prepareStatement("UPDATE sessions SET facts = ? WHERE id = ?").use { stmt ->
        stmt.setString(1, factsJson)
        stmt.setLong(2, sessionId)
        stmt.executeUpdate()
    }
}

fun loadFacts(sessionId: Long): String? {
    connection.prepareStatement("SELECT facts FROM sessions WHERE id = ?").use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) return rs.getString("facts")
        }
    }
    return null
}

fun createBranch(sessionId: Long, name: String, checkpoint: Int): Long {
    connection.prepareStatement(
        "INSERT INTO branches (session_id, name, checkpoint, created_at) VALUES (?, ?, ?, ?)"
    ).use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.setString(2, name)
        stmt.setInt(3, checkpoint)
        stmt.setString(4, Instant.now().toString())
        stmt.executeUpdate()
    }
    return connection.prepareStatement(
        "SELECT id FROM branches WHERE session_id = ? AND name = ?"
    ).use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.setString(2, name)
        stmt.executeQuery().use { rs -> rs.next(); rs.getLong("id") }
    }
}

data class BranchInfo(val id: Long, val name: String, val checkpoint: Int)

fun loadBranches(sessionId: Long): List<BranchInfo> {
    val results = mutableListOf<BranchInfo>()
    connection.prepareStatement(
        "SELECT id, name, checkpoint FROM branches WHERE session_id = ? ORDER BY id"
    ).use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.executeQuery().use { rs ->
            while (rs.next())
                results += BranchInfo(rs.getLong("id"), rs.getString("name"), rs.getInt("checkpoint"))
        }
    }
    return results
}

fun updateActiveBranch(sessionId: Long, branchId: Long?) {
    connection.prepareStatement("UPDATE sessions SET active_branch_id = ? WHERE id = ?").use { stmt ->
        if (branchId != null) stmt.setLong(1, branchId)
        else stmt.setNull(1, java.sql.Types.INTEGER)
        stmt.setLong(2, sessionId)
        stmt.executeUpdate()
    }
}

fun loadActiveBranchId(sessionId: Long): Long? {
    connection.prepareStatement("SELECT active_branch_id FROM sessions WHERE id = ?").use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) return rs.getLong("active_branch_id").takeIf { !rs.wasNull() }
        }
    }
    return null
}
```

Also update `clearMessages` to clear branches and facts:

```kotlin
fun clearMessages(sessionId: Long) {
    connection.prepareStatement("DELETE FROM messages WHERE session_id = ?").use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.executeUpdate()
    }
    connection.prepareStatement("DELETE FROM branches WHERE session_id = ?").use { stmt ->
        stmt.setLong(1, sessionId)
        stmt.executeUpdate()
    }
    updateSummaryState(sessionId, null, 0)
    updateFacts(sessionId, null)
    updateActiveBranch(sessionId, null)
}
```

**Step 3: Run all existing tests to confirm no regressions**

```bash
./gradlew test --tests "org.example.SessionRepositoryTest"
./gradlew test
```
Expected: all existing tests PASS

**Step 4: Add new repository tests for strategy/facts/branches**

Add to `SessionRepositoryTest.kt`:

```kotlin
@Test
fun `createSession stores strategy`() {
    val record = repository.createSession("test-strategy", null, StrategyType.SLIDING_WINDOW)
    assertEquals(StrategyType.SLIDING_WINDOW, record.strategy)
    val loaded = repository.listSessions().first { it.name == "test-strategy" }
    assertEquals(StrategyType.SLIDING_WINDOW, loaded.strategy)
}

@Test
fun `updateFacts and loadFacts round trip`() {
    val record = repository.createSession("facts-test", null)
    repository.updateFacts(record.id, """{"цель":"тест"}""")
    assertEquals("""{"цель":"тест"}""", repository.loadFacts(record.id))
}

@Test
fun `loadFacts returns null when not set`() {
    val record = repository.createSession("no-facts", null)
    assertNull(repository.loadFacts(record.id))
}

@Test
fun `createBranch and loadBranches round trip`() {
    val record = repository.createSession("branch-test", null)
    val branchId = repository.createBranch(record.id, "alt", 4)
    assertTrue(branchId > 0)
    val branches = repository.loadBranches(record.id)
    assertEquals(1, branches.size)
    assertEquals("alt", branches[0].name)
    assertEquals(4, branches[0].checkpoint)
}

@Test
fun `appendMessages with branchId stored and loadMessages filtered`() {
    val record = repository.createSession("br-msg-test", null)
    val branchId = repository.createBranch(record.id, "b1", 0)
    val trunkMsgs = listOf(Message("user", "trunk"))
    val branchMsgs = listOf(Message("user", "branch"))
    repository.appendMessages(record.id, trunkMsgs, 0, null)
    repository.appendMessages(record.id, branchMsgs, 0, branchId)
    val trunk = repository.loadMessages(record.id, null)
    val branch = repository.loadMessages(record.id, branchId)
    assertEquals(1, trunk.size)
    assertEquals("trunk", trunk[0].content)
    assertEquals(1, branch.size)
    assertEquals("branch", branch[0].content)
}

@Test
fun `updateActiveBranch and loadActiveBranchId`() {
    val record = repository.createSession("active-br", null)
    val id = repository.createBranch(record.id, "main", 0)
    repository.updateActiveBranch(record.id, id)
    assertEquals(id, repository.loadActiveBranchId(record.id))
    repository.updateActiveBranch(record.id, null)
    assertNull(repository.loadActiveBranchId(record.id))
}
```

**Step 5: Run all tests**

```bash
./gradlew test
```
Expected: all PASS

**Step 6: Commit**

```bash
git add src/main/kotlin/SessionRepository.kt src/test/kotlin/SessionRepositoryTest.kt
git commit -m "feat: add strategy/facts/branches to SessionRepository and DB schema"
```

---

### Task 8: Update CliAgent

**Files:**
- Modify: `src/main/kotlin/CliAgent.kt`

This is the largest change. Rewrite `CliAgent.kt` as follows. Key changes:
1. `createNewSession` prompts for strategy choice
2. `run` instantiates the right strategy based on `record.strategy`
3. Main chat loop handles `/facts`, `/forget`, `/branch`, `/switch`, `/branches` per strategy
4. `printStats` uses `getStrategyInfo()`
5. Save logic is strategy-aware

```kotlin
// src/main/kotlin/CliAgent.kt
package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)
    private val repository = SessionRepository()

    fun run() {
        println("CLI AI Agent | ${client.model}")
        val (record, history) = selectOrCreateSession()
        val session = buildSession(record, history)
        val strategyLabel = record.strategy.name.lowercase().replace('_', ' ')
        println("Стратегия: $strategyLabel")
        printHelp(record.strategy)

        var savedTrunkCount = history.size

        try {
            while (true) {
                print("Вы: ")
                val input = readlnOrNull()?.trim() ?: break
                when {
                    input.lowercase() == "exit" -> break
                    input == "/reset" -> handleReset(record, session)
                        .also { savedTrunkCount = 0 }
                    input == "/delete" -> {
                        repository.deleteSession(record.id)
                        println("Сессия «${record.name}» удалена.\n")
                        break
                    }
                    input == "/facts" && record.strategy == StrategyType.STICKY_FACTS -> {
                        val sf = session.strategy as StickyFactsStrategy
                        if (sf.facts.isEmpty()) println("Фактов нет.\n")
                        else sf.facts.forEach { (k, v) -> println("  $k: $v") }.also { println() }
                    }
                    input.startsWith("/forget ") && record.strategy == StrategyType.STICKY_FACTS -> {
                        val key = input.removePrefix("/forget ").trim()
                        (session.strategy as StickyFactsStrategy).removeFact(key)
                        repository.updateFacts(record.id, (session.strategy as StickyFactsStrategy).factsJson)
                        println("Факт «$key» удалён.\n")
                    }
                    input.startsWith("/branch ") && record.strategy == StrategyType.BRANCHING -> {
                        val name = input.removePrefix("/branch ").trim()
                        val bs = session.strategy as BranchingStrategy
                        try {
                            val branch = bs.createBranch(name)
                            val branchId = repository.createBranch(record.id, name, branch.checkpointPosition)
                            bs.updateBranchId(name, branchId)
                            repository.updateActiveBranch(record.id, branchId)
                            println("Ветка «$name» создана от позиции ${branch.checkpointPosition}. Вы на ветке «$name».\n")
                        } catch (e: IllegalArgumentException) {
                            println("Ошибка: ${e.message}\n")
                        }
                    }
                    input.startsWith("/switch ") && record.strategy == StrategyType.BRANCHING -> {
                        val name = input.removePrefix("/switch ").trim()
                        val bs = session.strategy as BranchingStrategy
                        if (bs.switchTo(name)) {
                            val activeId = bs.activeBranch?.id
                            repository.updateActiveBranch(record.id, activeId)
                            println("Переключено на ветку «${bs.activeBranch?.name ?: "trunk"}».\n")
                        } else {
                            println("Ветка «$name» не найдена.\n")
                        }
                    }
                    input == "/branches" && record.strategy == StrategyType.BRANCHING -> {
                        val bs = session.strategy as BranchingStrategy
                        val active = bs.activeBranch?.name ?: "trunk"
                        val marker = if (bs.isOnTrunk) "* " else "  "
                        println("${marker}trunk (${bs.trunkMessages.size} сообщ.)")
                        bs.branches.forEach { b ->
                            val m = if (b.name == active) "* " else "  "
                            println("$m${b.name} (${b.messages.size} сообщ., от позиции ${b.checkpointPosition})")
                        }
                        println()
                    }
                    input.isEmpty() -> continue
                    else -> {
                        val reply = session.chat(input)
                        println("\nАгент: $reply\n")
                        saveAfterChat(session, record, savedTrunkCount)
                        when (record.strategy) {
                            StrategyType.SUMMARY, StrategyType.SLIDING_WINDOW -> savedTrunkCount += 2
                            StrategyType.STICKY_FACTS -> savedTrunkCount += 2
                            StrategyType.BRANCHING -> {
                                val bs = session.strategy as BranchingStrategy
                                if (bs.isOnTrunk) savedTrunkCount += 2
                            }
                        }
                        printStats(session)
                    }
                }
            }
        } finally {
            repository.close()
        }
    }

    private fun saveAfterChat(session: ConversationSession, record: SessionRecord, savedTrunkCount: Int) {
        when (record.strategy) {
            StrategyType.SUMMARY -> {
                val ss = session.strategy as SummaryStrategy
                repository.appendMessages(record.id, session.history.takeLast(2), savedTrunkCount)
                repository.updateSummaryState(record.id, ss.summary, ss.summarizedUpTo)
            }
            StrategyType.SLIDING_WINDOW -> {
                repository.appendMessages(record.id, session.history.takeLast(2), savedTrunkCount)
            }
            StrategyType.STICKY_FACTS -> {
                val sf = session.strategy as StickyFactsStrategy
                repository.appendMessages(record.id, session.history.takeLast(2), savedTrunkCount)
                repository.updateFacts(record.id, sf.factsJson)
            }
            StrategyType.BRANCHING -> {
                val bs = session.strategy as BranchingStrategy
                val ab = bs.activeBranch
                if (ab != null) {
                    repository.appendMessages(record.id, session.history.takeLast(2), ab.savedMessageCount, ab.id)
                    ab.savedMessageCount += 2
                    repository.updateActiveBranch(record.id, ab.id)
                } else {
                    repository.appendMessages(record.id, session.history.takeLast(2), bs.trunkSavedCount)
                    bs.trunkSavedCount += 2
                }
            }
        }
    }

    private fun buildSession(record: SessionRecord, history: List<Message>): ConversationSession {
        return when (record.strategy) {
            StrategyType.SUMMARY -> {
                val (summary, summarizedUpTo) = repository.loadSummaryState(record.id)
                val strategy = SummaryStrategy(client, tailSize = 10, summaryEvery = 10)
                strategy.restoreState(history, summary, summarizedUpTo)
                if (history.isNotEmpty()) {
                    val note = if (summary != null) ", summary: ${summarizedUpTo} сообщ." else ""
                    println("Загружена сессия «${record.name}» (${history.size / 2} обменов$note)\n")
                }
                ConversationSession(client, record.systemPrompt, strategy)
            }
            StrategyType.SLIDING_WINDOW -> {
                val strategy = SlidingWindowStrategy(windowSize = 10)
                strategy.restoreState(history)
                if (history.isNotEmpty()) println("Загружена сессия «${record.name}» (${history.size / 2} обменов)\n")
                ConversationSession(client, record.systemPrompt, strategy)
            }
            StrategyType.STICKY_FACTS -> {
                val strategy = StickyFactsStrategy(client, tailSize = 10)
                val factsJson = repository.loadFacts(record.id)
                strategy.restoreState(history, factsJson)
                if (history.isNotEmpty()) {
                    println("Загружена сессия «${record.name}» (${history.size / 2} обменов, ${strategy.facts.size} фактов)\n")
                }
                ConversationSession(client, record.systemPrompt, strategy)
            }
            StrategyType.BRANCHING -> {
                val strategy = BranchingStrategy()
                val trunkMsgs = repository.loadMessages(record.id, branchId = null)
                val branchInfos = repository.loadBranches(record.id)
                val activeBranchId = repository.loadActiveBranchId(record.id)
                val branches = branchInfos.map { bi ->
                    val msgs = repository.loadMessages(record.id, branchId = bi.id)
                    BranchRecord(bi.id, bi.name, bi.checkpoint, msgs.toMutableList(), savedMessageCount = msgs.size)
                }
                strategy.restoreState(trunkMsgs, branches, activeBranchId)
                if (trunkMsgs.isNotEmpty() || branches.isNotEmpty()) {
                    val branchLabel = strategy.activeBranch?.name ?: "trunk"
                    println("Загружена сессия «${record.name}» (${branches.size} веток, активна: $branchLabel)\n")
                }
                ConversationSession(client, record.systemPrompt, strategy)
            }
        }
    }

    private fun handleReset(record: SessionRecord, session: ConversationSession) {
        repository.clearMessages(record.id)
        session.reset()
        println("История очищена.\n")
    }

    private fun printHelp(strategy: StrategyType) {
        val base = "exit, /reset, /delete"
        val extra = when (strategy) {
            StrategyType.STICKY_FACTS -> ", /facts, /forget <ключ>"
            StrategyType.BRANCHING -> ", /branch <имя>, /switch <имя>, /branches"
            else -> ""
        }
        println("Команды: $base$extra\n")
    }

    private fun printStats(session: ConversationSession) {
        val last = session.lastUsage
        val total = session.sessionUsage
        val lastCost = PricingProvider.costUsd(client.model, last)
        val totalCost = PricingProvider.costUsd(client.model, total)
        val lastCostStr = lastCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
        val totalCostStr = totalCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
        println(
            "Токены — запрос: вход=${last.inputTokens}, выход=${last.outputTokens}$lastCostStr" +
            " | сессия: вход=${total.inputTokens}, выход=${total.outputTokens}$totalCostStr"
        )

        val contextMax = PricingProvider.getContextWindow(client.model)
        val usedPct = if (contextMax > 0) last.inputTokens * 100.0 / contextMax else 0.0
        val info = session.strategy.getStrategyInfo()
        val extraStr = info.extra.entries.joinToString(", ") { (k, v) -> "$k: $v" }
            .let { if (it.isNotEmpty()) " | $it" else "" }
        println(
            "Контекст: ${last.inputTokens}/$contextMax токенов " +
            "(${"%.1f".format(usedPct).replace(',', '.')}%) | " +
            "стратегия: ${info.type.name.lowercase()} | " +
            "отправлено: ${info.sentMessages}/${info.totalMessages} сообщ.$extraStr"
        )

        // For SUMMARY: print summary token cost
        if (session.strategy is SummaryStrategy) {
            val sumUsage = (session.strategy as SummaryStrategy).summaryUsage
            if (sumUsage.inputTokens > 0) {
                val sumCost = PricingProvider.costUsd(client.model, sumUsage)
                val sumCostStr = sumCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
                println("Суммаризация (накоплено): вход=${sumUsage.inputTokens}, выход=${sumUsage.outputTokens}$sumCostStr")
            }
        }
        // For STICKY_FACTS: print facts token cost
        if (session.strategy is StickyFactsStrategy) {
            val factsUsage = (session.strategy as StickyFactsStrategy).factsUsage
            if (factsUsage.inputTokens > 0) {
                val factsCost = PricingProvider.costUsd(client.model, factsUsage)
                val factsCostStr = factsCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
                println("Facts-токены (накоплено): вход=${factsUsage.inputTokens}, выход=${factsUsage.outputTokens}$factsCostStr")
            }
        }
        println()
    }

    private fun selectOrCreateSession(): Pair<SessionRecord, List<Message>> {
        val sessions = repository.listSessions()
        if (sessions.isEmpty()) {
            println("Сохранённых сессий нет. Создаём первую.\n")
            return createNewSession() to emptyList()
        }
        println("Сессии:")
        sessions.forEachIndexed { i, s ->
            println("  ${i + 1}. ${s.name} [${s.strategy.name.lowercase()}]")
        }
        println("  ${sessions.size + 1}. Новая сессия")
        println()
        while (true) {
            print("Выбор (1–${sessions.size + 1}): ")
            val choice = readlnOrNull()?.trim()?.toIntOrNull() ?: continue
            when {
                choice in 1..sessions.size -> {
                    val rec = sessions[choice - 1]
                    return rec to repository.loadMessages(rec.id)
                }
                choice == sessions.size + 1 -> return createNewSession() to emptyList()
                else -> println("Неверный выбор.\n")
            }
        }
    }

    private fun createNewSession(): SessionRecord {
        print("Имя сессии: ")
        val name = readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
        print("System prompt (Enter чтобы пропустить): ")
        val prompt = readlnOrNull()?.trim().takeIf { !it.isNullOrEmpty() }
        println()
        println("Стратегии управления контекстом:")
        println("  1. Summary      — суммаризация + хвост сообщений")
        println("  2. Sliding Window — последние N сообщений")
        println("  3. Sticky Facts   — ключевые факты + хвост")
        println("  4. Branching      — ветки диалога")
        val strategy = readStrategyChoice()
        println()
        return repository.createSession(name, prompt, strategy)
    }

    private fun readStrategyChoice(): StrategyType {
        while (true) {
            print("Выбор стратегии (1-4): ")
            return when (readlnOrNull()?.trim()) {
                "1" -> StrategyType.SUMMARY
                "2" -> StrategyType.SLIDING_WINDOW
                "3" -> StrategyType.STICKY_FACTS
                "4" -> StrategyType.BRANCHING
                else -> { println("Неверный выбор."); continue }
            }
        }
    }
}
```

**Step 2: Run all tests**

```bash
./gradlew test
```
Expected: all tests PASS (CliAgent has no dedicated unit test, but related tests should pass)

**Step 3: Commit**

```bash
git add src/main/kotlin/CliAgent.kt
git commit -m "feat: update CliAgent with strategy selection, new commands, and strategy-aware persistence"
```

---

### Task 9: Final cleanup and full test run

**Step 1: Run full test suite**

```bash
./gradlew test
```
Expected: all tests PASS with 0 failures

**Step 2: Quick smoke check — compile the runnable jar**

```bash
./gradlew build -x test
```
Expected: BUILD SUCCESSFUL

**Step 3: Update MEMORY.md with architectural notes**

Update `/Users/user/.claude/projects/-Users-user-IdeaProjects-AiChallenge/memory/MEMORY.md` to reflect the new strategy architecture.

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat: complete Day 10 — 3 context strategies (SlidingWindow, StickyFacts, Branching)"
```

---

## Summary of New Files

| File | Purpose |
|------|---------|
| `ContextStrategy.kt` | Interface + StrategyType + StrategyInfo |
| `SlidingWindowStrategy.kt` | Keep last N messages only |
| `StickyFactsStrategy.kt` | Auto-extract facts + tail |
| `BranchingStrategy.kt` | Branching from trunk checkpoints |
| `SummaryStrategy.kt` | Wrap existing ContextManager |
| `SlidingWindowStrategyTest.kt` | 7 tests |
| `StickyFactsStrategyTest.kt` | 10 tests |
| `BranchingStrategyTest.kt` | 15 tests |
| `SummaryStrategyTest.kt` | 5 tests |
