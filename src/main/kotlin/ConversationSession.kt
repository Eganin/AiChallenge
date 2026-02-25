// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    private val windowSize: Int = 20
) {
    val history: MutableList<Message> = mutableListOf()

    var lastUsage = TokenUsage(0, 0)
        private set

    var sessionUsage = TokenUsage(0, 0)
        private set

    fun chat(userMessage: String): String {
        history.add(Message("user", userMessage))
        val window = if (windowSize > 0) history.takeLast(windowSize) else history.toList()
        val response = client.ask(window, systemPrompt)
        history.add(Message("assistant", response.text))
        lastUsage = response.usage
        sessionUsage = TokenUsage(
            inputTokens = sessionUsage.inputTokens + response.usage.inputTokens,
            outputTokens = sessionUsage.outputTokens + response.usage.outputTokens
        )
        return response.text
    }

    fun reset() {
        history.clear()
        lastUsage = TokenUsage(0, 0)
        sessionUsage = TokenUsage(0, 0)
    }
}
