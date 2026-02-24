// src/main/kotlin/ConversationSession.kt
package org.example

class ConversationSession(
    private val client: ClaudeClient,
    private val systemPrompt: String? = null
) {
    val history: MutableList<Message> = mutableListOf()

    fun chat(userMessage: String): String {
        history.add(Message("user", userMessage))
        val reply = client.ask(history.toList(), systemPrompt)
        history.add(Message("assistant", reply))
        return reply
    }

    fun reset() {
        history.clear()
    }
}
