// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    val strategy: ContextStrategy
) {
    val history: List<Message> get() = strategy.fullHistory

    var lastUsage = TokenUsage(0, 0)
        private set

    var sessionUsage = TokenUsage(0, 0)
        private set

    fun chat(userMessage: String): String {
        strategy.onUserMessage(Message("user", userMessage))
        val contextMessages = strategy.buildContextMessages()
        val response = client.ask(contextMessages, systemPrompt)
        strategy.onAssistantMessage(Message("assistant", response.text))
        lastUsage = response.usage
        sessionUsage = TokenUsage(
            inputTokens = sessionUsage.inputTokens + response.usage.inputTokens,
            outputTokens = sessionUsage.outputTokens + response.usage.outputTokens
        )
        return response.text
    }

    fun reset() {
        strategy.reset()
        lastUsage = TokenUsage(0, 0)
        sessionUsage = TokenUsage(0, 0)
    }
}
