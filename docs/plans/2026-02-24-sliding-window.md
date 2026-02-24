# Sliding Window Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Limit the number of messages sent to the Claude API to the most recent `windowSize` entries, reducing token usage on long sessions.

**Architecture:** Add `windowSize: Int = 20` parameter to `ConversationSession`. In `chat()`, replace `history.toList()` with a windowed slice. The full `history` is preserved in memory and SQLite — only what is sent to the API is limited. `CliAgent` explicitly passes `windowSize = 20`.

**Tech Stack:** Kotlin, kotlin.test (no mocking — stubs via `object : ClaudeClient`)

---

### Task 1: Add windowSize to ConversationSession

**Files:**
- Modify: `src/main/kotlin/ConversationSession.kt`
- Modify: `src/test/kotlin/ConversationSessionTest.kt`

**Step 1: Write the failing tests**

In `src/test/kotlin/ConversationSessionTest.kt`:

1. Update `makeSession` to accept a `windowSize` parameter (add it before the closing `}` of the function — add `windowSize: Int = 0` and thread it through):

```kotlin
    private fun makeSession(
        systemPrompt: String? = null,
        windowSize: Int = 0,
        fakeReply: (List<Message>) -> String = { "reply" }
    ): ConversationSession {
        val stubClient = object : ClaudeClient("fake-key", "fake-model") {
            override fun ask(messages: List<Message>, systemPrompt: String?, maxTokens: Int): String {
                return fakeReply(messages)
            }
        }
        return ConversationSession(stubClient, systemPrompt, windowSize)
    }
```

Note: `windowSize = 0` in the helper means "no limit", so all existing tests continue to pass unchanged.

2. Add these three new tests inside `ConversationSessionTest` (before the closing `}`):

```kotlin
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
        // Before third ask: history = [u1,a1,u2,a2,u3] → 5 messages sent

        assertEquals(5, capturedMessages.size)
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.example.ConversationSessionTest" 2>&1 | tail -20`
Expected: compilation error — `ConversationSession` does not accept a third argument

**Step 3: Update ConversationSession**

Replace the entire contents of `src/main/kotlin/ConversationSession.kt` with:

```kotlin
// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    private val windowSize: Int = 20
) {
    val history: MutableList<Message> = mutableListOf()

    fun chat(userMessage: String): String {
        history.add(Message("user", userMessage))
        val window = if (windowSize > 0) history.takeLast(windowSize) else history.toList()
        val reply = client.ask(window, systemPrompt)
        history.add(Message("assistant", reply))
        return reply
    }

    fun reset() {
        history.clear()
    }
}
```

**Step 4: Run all tests to verify they pass**

Run: `./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass

**Step 5: Commit**

```bash
git add src/main/kotlin/ConversationSession.kt src/test/kotlin/ConversationSessionTest.kt
git commit -m "feat: add sliding window to ConversationSession"
```

---

### Task 2: Update CliAgent to explicitly pass windowSize

**Files:**
- Modify: `src/main/kotlin/CliAgent.kt`

**Step 1: Update the ConversationSession constructor call**

In `src/main/kotlin/CliAgent.kt`, find:

```kotlin
        val session = ConversationSession(client, record.systemPrompt)
```

Replace with:

```kotlin
        val session = ConversationSession(client, record.systemPrompt, windowSize = 20)
```

**Step 2: Run all tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/CliAgent.kt
git commit -m "feat: explicitly set windowSize = 20 in CliAgent"
```
