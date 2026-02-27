# Context Management Strategies — Design

**Date:** 2026-02-27
**Branch:** feature/challenge_2_4
**Objective:** Implement 3 context management strategies with a switcher.

---

## Overview

Replace the hard-wired `ContextManager` with a pluggable `ContextStrategy` interface.
Each session has a fixed strategy (chosen at creation time, stored in DB).
Four strategies total: existing Summary+Tail (renamed SUMMARY) + three new ones.

---

## Architecture

### ContextStrategy Interface

```kotlin
enum class StrategyType { SUMMARY, SLIDING_WINDOW, STICKY_FACTS, BRANCHING }

interface ContextStrategy {
    val type: StrategyType
    val fullHistory: List<Message>
    fun addMessage(msg: Message)
    fun onAssistantResponse(msg: Message)  // hook: facts extraction, summarization
    fun buildContextMessages(): List<Message>
    fun reset()
    fun getStrategyInfo(): StrategyInfo
}

data class StrategyInfo(
    val type: StrategyType,
    val totalMessages: Int,
    val sentMessages: Int,
    val extra: Map<String, String> = emptyMap()
)
```

### ConversationSession

Accepts `ContextStrategy` instead of `tailSize`/`summaryEvery`.
Calls `strategy.addMessage()` for user messages, `strategy.onAssistantResponse()` after getting reply.

---

## Strategy Implementations

### 1. SlidingWindowStrategy
- Stores full history for DB persistence
- `buildContextMessages()` → `fullHistory.takeLast(windowSize)`
- No LLM calls. Cheapest strategy.
- Parameter: `windowSize: Int = 10`

### 2. StickyFactsStrategy
- Stores `fullHistory` + `facts: MutableMap<String, String>`
- `onAssistantResponse()` — separate LLM call: extract/update facts as JSON
- `buildContextMessages()` → `[facts-placeholder pair] + fullHistory.takeLast(tailSize)`
- Parameter: `tailSize: Int = 10`
- CLI: `/facts` (show), `/forget <key>` (remove)
- DB: facts stored as JSON in `sessions.facts` column

### 3. BranchingStrategy
- `trunkMessages`: shared messages before any checkpoint
- `branches: MutableList<Branch>` where `Branch(id, name, checkpointPosition, messages)`
- `activeBranchIndex`: which branch is currently active
- `buildContextMessages()` → `trunkMessages + activeBranch.messages`
- CLI: `/branch <name>`, `/switch <name>`, `/branches`
- DB: new `branches` table + `messages.branch_id` column

### 4. SummaryStrategy (existing, preserved)
- Current `ContextManager` logic: summarize old messages + keep tail
- Wrapped in a `SummaryStrategy` class implementing `ContextStrategy`

---

## Database Schema Changes

```sql
-- Migration (try/catch for existing DBs)
ALTER TABLE sessions ADD COLUMN strategy TEXT NOT NULL DEFAULT 'SUMMARY';
ALTER TABLE sessions ADD COLUMN facts TEXT;  -- JSON for STICKY_FACTS

ALTER TABLE messages ADD COLUMN branch_id INTEGER REFERENCES branches(id);

CREATE TABLE IF NOT EXISTS branches (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    checkpoint INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
```

---

## CliAgent Changes

- Session creation: prompt user to choose strategy (1=Summary, 2=SlidingWindow, 3=StickyFacts, 4=Branching)
- Session loading: read `strategy` from DB, instantiate correct implementation
- Strategy-specific commands shown only when relevant:
  - STICKY_FACTS: `/facts`, `/forget <key>`
  - BRANCHING: `/branch <name>`, `/switch <name>`, `/branches`
- `printStats()` uses `StrategyInfo` for display

---

## New Files

| File | Purpose |
|------|---------|
| `ContextStrategy.kt` | Interface + StrategyInfo + StrategyType |
| `SlidingWindowStrategy.kt` | Sliding window implementation |
| `StickyFactsStrategy.kt` | Sticky facts implementation |
| `BranchingStrategy.kt` | Branching implementation |

## Modified Files

| File | Changes |
|------|---------|
| `ConversationSession.kt` | Accept ContextStrategy instead of tailSize/summaryEvery |
| `ContextManager.kt` | Wrap in SummaryStrategy (or keep, adapt) |
| `SessionRepository.kt` | Add methods for facts, branches, strategy |
| `CliAgent.kt` | Strategy selection, new commands, updated stats |
| `DemoAgent.kt` | Use SummaryStrategy (backward compat) |

---

## CLI UX Example

```
Стратегии:
  1. Summary (суммаризация + хвост)
  2. Sliding Window (последние N сообщений)
  3. Sticky Facts (факты + хвост)
  4. Branching (ветки диалога)
Выбор стратегии (1-4): 3

--- Sticky Facts ---
Команды: exit, /reset, /delete, /facts, /forget <ключ>

Вы: Моя цель — написать CLI-агент на Kotlin
Агент: Отлично! Давайте начнём...

[После ответа агент автоматически извлекает факты]
Facts: {цель: "CLI-агент на Kotlin"}

Стратегия: STICKY_FACTS | факты: 1 | хвост: 2 сообщ.
```

```
--- Branching ---
Команды: exit, /reset, /delete, /branch <имя>, /switch <имя>, /branches

Вы: /branch альтернатива
Ветка «альтернатива» создана от позиции 4.

Вы: /branches
Ветки:
  * главная (4 сообщ.)
    альтернатива (0 сообщ.)

Вы: /switch альтернатива
Переключено на ветку «альтернатива».
```
