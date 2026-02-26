// src/main/kotlin/CliAgent.kt
package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)
    private val repository = SessionRepository()

    fun run() {
        println("CLI AI Agent | ${client.model}")

        val (record, history) = selectOrCreateSession()
        val (summary, summarizedUpTo) = repository.loadSummaryState(record.id)

        val session = ConversationSession(client, record.systemPrompt, tailSize = 10, summaryEvery = 10)
        session.contextManager.restoreState(history, summary, summarizedUpTo)

        if (history.isNotEmpty()) {
            val summaryNote = if (summary != null) ", summary есть (${summarizedUpTo} сообщ.)" else ""
            println("Загружена сессия «${record.name}» (${history.size / 2} обменов$summaryNote)\n")
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
                        println("История и summary очищены.\n")
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
                        repository.updateSummaryState(
                            record.id,
                            session.contextManager.summary,
                            session.contextManager.summarizedUpTo
                        )

                        println("\nАгент: $reply\n")
                        printStats(session)
                    }
                }
            }
        } finally {
            repository.close()
        }
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
        val cm = session.contextManager
        val summaryNote = if (cm.summary != null) "summary: ${cm.summarizedUpTo} сообщ. сжато" else "summary: нет"
        val tailNote = "хвост: ${minOf(cm.tailSize, cm.fullHistory.size).coerceAtLeast(0)} сообщ."
        println(
            "Контекст: ${last.inputTokens}/$contextMax токенов " +
            "(${"%.1f".format(usedPct).replace(',', '.')}%) | $summaryNote | $tailNote"
        )

        val sumUsage = cm.summaryUsage
        if (sumUsage.inputTokens > 0) {
            val sumCost = PricingProvider.costUsd(client.model, sumUsage)
            val sumCostStr = sumCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
            println("Суммаризация (накоплено): вход=${sumUsage.inputTokens}, выход=${sumUsage.outputTokens}$sumCostStr")
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
