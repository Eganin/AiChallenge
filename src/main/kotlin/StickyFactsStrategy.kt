package org.example

import org.json.JSONObject

class StickyFactsStrategy(
    private val client: ClaudeClient,
    val tailSize: Int = 10
) : ContextStrategy {

    private val _history = mutableListOf<Message>()
    private val _facts = mutableMapOf<String, String>()
    val facts: Map<String, String> get() = _facts

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
        val existingFacts = if (_facts.isEmpty()) "" else
            "Текущие факты:\n" + _facts.entries.joinToString("\n") { (k, v) -> "- $k: $v" } + "\n\n"
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
        parseFacts(response.text).forEach { (k, v) -> _facts[k] = v }
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
        if (_facts.isNotEmpty()) {
            val factsText = _facts.entries.joinToString("\n") { (k, v) -> "- $k: $v" }
            result.add(Message("user", "[Ключевые факты разговора]\n$factsText"))
            result.add(Message("assistant", "Понял, учту ключевые факты."))
        }
        result.addAll(tail)
        return result
    }

    override fun reset() {
        _history.clear()
        _facts.clear()
        factsUsage = TokenUsage(0, 0)
    }

    override fun getStrategyInfo() = StrategyInfo(
        type = StrategyType.STICKY_FACTS,
        totalMessages = _history.size,
        sentMessages = if (tailSize > 0) minOf(tailSize, _history.size) else _history.size,
        extra = mapOf("факты" to _facts.size.toString())
    )

    fun removeFact(key: String) { _facts.remove(key) }

    val factsJson: String
        get() = if (_facts.isEmpty()) "{}" else
            JSONObject(_facts as Map<*, *>).toString()

    fun restoreState(messages: List<Message>, factsJson: String?) {
        _history.clear()
        _history.addAll(messages)
        _facts.clear()
        if (!factsJson.isNullOrBlank()) {
            parseFacts(factsJson).forEach { (k, v) -> _facts[k] = v }
        }
    }
}
