// src/main/kotlin/CliAgent.kt
package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)

    fun run() {
        println("CLI AI Agent | ${client.model}")
        println("Для выхода введите 'exit', для сброса истории — '/reset'\n")

        print("System prompt (Enter чтобы пропустить): ")
        val rawPrompt = readlnOrNull()?.trim()
        val systemPrompt = if (rawPrompt.isNullOrEmpty()) null else rawPrompt

        val session = ConversationSession(client, systemPrompt)
        println()

        while (true) {
            print("Вы: ")
            val input = readlnOrNull()?.trim() ?: break
            when {
                input.lowercase() == "exit" -> break
                input == "/reset" -> {
                    session.reset()
                    println("История очищена.\n")
                }
                input.isEmpty() -> continue
                else -> println("\nАгент: ${session.chat(input)}\n")
            }
        }
    }
}
