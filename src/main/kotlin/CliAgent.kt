package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)

    fun run() {
        println("CLI AI Agent | ${client.model}")
        println("Для выхода введите 'exit'\n")

        while (true) {
            print("Вы: ")
            val input = readlnOrNull()?.trim() ?: break
            if (input.lowercase() == "exit") break
            if (input.isEmpty()) continue

            println("\nАгент: ${client.ask(input)}\n")
        }
    }
}