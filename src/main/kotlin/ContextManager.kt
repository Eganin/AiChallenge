package org.example

data class ContextWindowInfo(
    val inputTokensUsed: Int,
    val contextWindowMax: Int,
    val totalMessages: Int,
    val summarizedMessages: Int,
    val tailSize: Int,
    val hasSummary: Boolean,
    val summaryUsage: TokenUsage
) {
    val usedPercent: Double get() =
        if (contextWindowMax > 0) inputTokensUsed * 100.0 / contextWindowMax else 0.0
}

class ContextManager(
    private val client: ClaudeClient,
    val tailSize: Int = 10,
    val summaryEvery: Int = 10
) {
    val fullHistory: MutableList<Message> = mutableListOf()

    var summary: String? = null
        private set

    var summarizedUpTo: Int = 0
        private set

    var summaryUsage: TokenUsage = TokenUsage(0, 0)
        private set

    fun addMessage(msg: Message) {
        fullHistory.add(msg)
        if (msg.role == "assistant" && summaryEvery > 0 && tailSize > 0) {
            maybeUpdateSummary()
        }
    }

    private fun maybeUpdateSummary() {
        val oldCount = (fullHistory.size - tailSize).coerceAtLeast(0)
        val unsummarizedCount = oldCount - summarizedUpTo
        if (unsummarizedCount >= summaryEvery) {
            val toSummarize = fullHistory.subList(summarizedUpTo, oldCount).toList()
            val response = buildIncrementalSummary(toSummarize)
            summary = response.text
            summarizedUpTo = oldCount
            summaryUsage = TokenUsage(
                inputTokens = summaryUsage.inputTokens + response.usage.inputTokens,
                outputTokens = summaryUsage.outputTokens + response.usage.outputTokens
            )
        }
    }

    private fun buildIncrementalSummary(newMessages: List<Message>): ClaudeResponse {
        val existingSummary = summary
        val promptContent = buildString {
            if (existingSummary != null) {
                appendLine("Предыдущее краткое содержание разговора:")
                appendLine(existingSummary)
                appendLine()
                appendLine("Новые сообщения для включения в краткое содержание:")
            } else {
                appendLine("Разговор для суммаризации:")
            }
            newMessages.forEach { msg ->
                val role = if (msg.role == "user") "Пользователь" else "Ассистент"
                appendLine("$role: ${msg.content}")
            }
            appendLine()
            append(
                "Составь краткое содержание всего разговора в 3-5 предложениях на русском языке. " +
                "Сохрани ключевые факты, вопросы и ответы."
            )
        }
        return client.ask(
            messages = listOf(Message("user", promptContent)),
            systemPrompt = "Ты помощник, который составляет краткие содержания диалогов. Будь точен и лаконичен.",
            maxTokens = 512
        )
    }

    fun buildContextMessages(): List<Message> {
        val tail = if (tailSize > 0) fullHistory.takeLast(tailSize) else fullHistory.toList()
        val result = mutableListOf<Message>()
        val s = summary
        if (s != null) {
            result.add(Message("user", "[Краткое содержание предыдущего разговора]\n$s"))
            result.add(Message("assistant", "Понял, учту контекст предыдущего разговора."))
        }
        result.addAll(tail)
        return result
    }

    fun contextInfo(inputTokensUsed: Int, contextWindowMax: Int): ContextWindowInfo =
        ContextWindowInfo(
            inputTokensUsed = inputTokensUsed,
            contextWindowMax = contextWindowMax,
            totalMessages = fullHistory.size,
            summarizedMessages = summarizedUpTo,
            tailSize = if (tailSize > 0) minOf(tailSize, fullHistory.size) else fullHistory.size,
            hasSummary = summary != null,
            summaryUsage = summaryUsage
        )

    fun restoreState(messages: List<Message>, summary: String?, summarizedUpTo: Int) {
        fullHistory.clear()
        fullHistory.addAll(messages)
        this.summary = summary
        this.summarizedUpTo = summarizedUpTo
    }

    fun reset() {
        fullHistory.clear()
        summary = null
        summarizedUpTo = 0
        summaryUsage = TokenUsage(0, 0)
    }
}
