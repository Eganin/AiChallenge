# Session Persistence Design

**Date:** 2026-02-24
**Feature:** Сохранение истории диалога между запусками агента

## Цель

Агент должен сохранять историю диалога на диск, чтобы при следующем запуске продолжить разговор с того места, где он остановился. Поддерживается несколько именованных сессий.

## Формат хранения

SQLite-база данных `agent.db` в корне проекта.

### Схема

```sql
CREATE TABLE sessions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT UNIQUE NOT NULL,
    system_prompt TEXT,
    created_at   TEXT NOT NULL
);

CREATE TABLE messages (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role       TEXT NOT NULL,
    content    TEXT NOT NULL,
    position   INTEGER NOT NULL
);
```

## Архитектура

```
CliAgent
  ├── SessionRepository  (новый)  — CRUD-операции над sessions и messages
  └── ConversationSession          — не изменяется
```

`SessionRepository` инкапсулирует всю работу с БД. `CliAgent` использует его на старте и после каждого обмена сообщениями.

## Новые / изменённые файлы

| Файл | Изменение |
|------|-----------|
| `SessionRepository.kt` | Новый — работа с SQLite через JDBC |
| `CliAgent.kt` | Обновлён — меню сессий, команды `/delete` |
| `build.gradle.kts` | Добавить `org.xerial:sqlite-jdbc:3.47.1.0` |

## Поведение

| Событие | Действие |
|---------|----------|
| Запуск | Вывести список сессий → выбрать существующую или создать новую |
| Новая сессия | Спросить имя + system prompt, сохранить в `sessions` |
| Каждое сообщение | Дописать пару user/assistant в `messages` |
| `/reset` | Удалить все `messages` сессии, очистить `history` в памяти |
| `/delete` | Удалить сессию и все её сообщения, выйти из программы |
| `exit` | Выйти (история уже сохранена) |

## Зависимость

```kotlin
implementation("org.xerial:sqlite-jdbc:3.47.1.0")
```
