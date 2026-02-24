// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null,
    private val windowSize: Int = 20
) {
    val history: MutableList<Message> = mutableListOf()

    fun chat(userMessage: String): String {
        history.add(Message("user", userMessage))
        val window = if (windowSize > 0) history.takeLast(windowSize) else history.toList()
        val reply = client.ask(window, systemPrompt)
        history.add(Message("assistant", reply))
        return reply
    }

    fun reset() {
        history.clear()
    }
}
