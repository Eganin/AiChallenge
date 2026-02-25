// src/main/kotlin/DemoAgent.kt
package org.example

class DemoAgent(private val client: ClaudeClient) {

    constructor(apiKey: String) : this(ClaudeClient(apiKey, "claude-haiku-4-5-20251001"))

    fun run() {
        printHeader("ДЕМО 1: КОРОТКИЙ ДИАЛОГ (3 обмена)")
        runShortDemo()
        printHeader("ДЕМО 2: ДЛИННЫЙ ДИАЛОГ (15 обменов, продолжается сам)")
        runLongDemo()
        printHeader("ДЕМО 3: ПРЕВЫШЕНИЕ ЛИМИТА ТОКЕНОВ МОДЕЛИ")
        runOverflowDemo()
        println("\n${"=".repeat(60)}")
        println("  ДЕМОНСТРАЦИЯ ЗАВЕРШЕНА")
        println("${"=".repeat(60)}\n")
    }

    fun runShortDemo(): ConversationSession {
        val session = ConversationSession(client, windowSize = 0)
        val messages = listOf(
            "Как называется столица Франции?",
            "Расскажи одну интересную историческую деталь об этом городе.",
            "Спасибо, этого достаточно."
        )
        for (msg in messages) {
            println("Пользователь: $msg")
            val reply = session.chat(msg)
            println("Агент: $reply")
            printUsage(session)
        }
        return session
    }

    fun runLongDemo(): ConversationSession {
        val session = ConversationSession(
            client,
            systemPrompt = "Ты историк. Всегда заканчивай свой ответ одним вопросом, чтобы продолжить беседу.",
            windowSize = 0
        )
        var nextMessage = "Давай обсудим историю Римской империи. Начни с основания."
        repeat(15) {
            println("Пользователь: $nextMessage")
            val reply = session.chat(nextMessage)
            println("Агент: $reply")
            printUsage(session)
            nextMessage = extractLastSentence(reply)
        }
        return session
    }

    private fun extractLastSentence(text: String): String {
        val sentences = text.trim().split(Regex("(?<=[.?!])\\s+"))
        return sentences.lastOrNull { it.isNotBlank() }?.trimEnd('.', '!') + "?"
    }

    fun runOverflowDemo() {
        val targetTokens = 210_000
        // "a " ≈ 1 token — sending ~210 000 tokens, limit is 200 000
        val bigMessage = "a ".repeat(targetTokens)
        println("Отправляем сообщение ~$targetTokens токенов (лимит модели claude-haiku-4-5: 200 000)...")
        val session = ConversationSession(client, windowSize = 0)
        try {
            session.chat(bigMessage)
            println("НЕОЖИДАННО: запрос прошёл без ошибки.")
        } catch (e: IllegalStateException) {
            println("ОШИБКА API (ожидаемо):")
            println("  ${e.message?.take(300)}")
            println("\nВывод: модель отклонила запрос, превышающий контекстное окно.")
        }
    }

    private fun printHeader(title: String) {
        println("\n${"=".repeat(60)}")
        println("  $title")
        println("${"=".repeat(60)}\n")
    }

    private fun printUsage(session: ConversationSession) {
        val last = session.lastUsage
        val total = session.sessionUsage
        val lastCost = PricingProvider.costUsd(client.model, last)
        val totalCost = PricingProvider.costUsd(client.model, total)
        val lastStr = lastCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
        val totalStr = totalCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
        println(
            "Токены — запрос: вход=${last.inputTokens}, выход=${last.outputTokens}$lastStr" +
            " | сессия: вход=${total.inputTokens}, выход=${total.outputTokens}$totalStr\n"
        )
    }
}
