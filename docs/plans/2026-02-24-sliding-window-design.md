# Sliding Window Design

**Date:** 2026-02-24
**Feature:** Ограничение числа сообщений, отправляемых в API, для экономии токенов

## Цель

Отправлять в Claude API только последние N сообщений истории, а не полную историю. Полная история по-прежнему хранится в памяти и в SQLite.

## Изменяемые файлы

| Файл | Что меняется |
|---|---|
| `ConversationSession.kt` | Добавить параметр `windowSize: Int = 20`, ограничить срез в `chat()` |
| `CliAgent.kt` | Передавать `windowSize = 20` при создании `ConversationSession` |
| `ConversationSessionTest.kt` | Тесты на поведение окна |

## Поведение

- `windowSize = 20` — в API уходят последние 20 сообщений (10 обменов)
- `windowSize = 0` — без ограничений (текущее поведение)
- `history` хранится полностью — в памяти и в SQLite ничего не теряется
- Только срез `history.takeLast(windowSize)` передаётся в `client.ask()`

## Интерфейс

```kotlin
class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    private val windowSize: Int = 20
)
```

Размер окна задаётся в `CliAgent` константой, пользователь его не видит.
