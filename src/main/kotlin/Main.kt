package org.example

import io.github.cdimascio.dotenv.dotenv

fun main() {
    val apiKey = dotenv()["ANTHROPIC_API_KEY"] ?: error("Задайте ANTHROPIC_API_KEY в .env")

    val agent = CliAgent(apiKey)
    agent.run()
}