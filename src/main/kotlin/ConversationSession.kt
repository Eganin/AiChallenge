// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    tailSize: Int = 5,
    summaryEvery: Int = 5
) {
    val contextManager = ContextManager(client, tailSize, summaryEvery)

    val history: List<Message> get() = contextManager.fullHistory

    var lastUsage = TokenUsage(0, 0)
        private set

    var sessionUsage = TokenUsage(0, 0)
        private set

    fun chat(userMessage: String): String {
        contextManager.addMessage(Message("user", userMessage))
        val contextMessages = contextManager.buildContextMessages()
        val response = client.ask(contextMessages, systemPrompt)
        contextManager.addMessage(Message("assistant", response.text))
        lastUsage = response.usage
        sessionUsage = TokenUsage(
            inputTokens = sessionUsage.inputTokens + response.usage.inputTokens,
            outputTokens = sessionUsage.outputTokens + response.usage.outputTokens
        )
        return response.text
    }

    fun reset() {
        contextManager.reset()
        lastUsage = TokenUsage(0, 0)
        sessionUsage = TokenUsage(0, 0)
    }
}
