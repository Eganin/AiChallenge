// src/main/kotlin/CliAgent.kt
package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)
    private val repository = SessionRepository()

    fun run() {
        println("CLI AI Agent | ${client.model}")

        val (record, history) = selectOrCreateSession()
        val session = ConversationSession(client, record.systemPrompt, windowSize = 20)
        session.history.addAll(history)

        if (history.isNotEmpty()) {
            println("Загружена сессия «${record.name}» (${history.size / 2} обменов)\n")
        }

        println("Команды: exit — выйти, /reset — очистить историю, /delete — удалить сессию\n")

        var savedCount = history.size

        try {
            while (true) {
                print("Вы: ")
                val input = readlnOrNull()?.trim() ?: break
                when {
                    input.lowercase() == "exit" -> break
                    input == "/reset" -> {
                        repository.clearMessages(record.id)
                        session.reset()
                        savedCount = 0
                        println("История очищена.\n")
                    }
                    input == "/delete" -> {
                        repository.deleteSession(record.id)
                        println("Сессия «${record.name}» удалена.\n")
                        break
                    }
                    input.isEmpty() -> continue
                    else -> {
                        val reply = session.chat(input)
                        repository.appendMessages(record.id, session.history.takeLast(2), savedCount)
                        savedCount += 2
                        println("\nАгент: $reply\n")
                    }
                }
            }
        } finally {
            repository.close()
        }
    }

    private fun selectOrCreateSession(): Pair<SessionRecord, List<Message>> {
        val sessions = repository.listSessions()

        if (sessions.isEmpty()) {
            println("Сохранённых сессий нет. Создаём первую.\n")
            return createNewSession() to emptyList()
        }

        println("Сессии:")
        sessions.forEachIndexed { i, s -> println("  ${i + 1}. ${s.name}") }
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
        return repository.createSession(name, prompt)
    }
}
