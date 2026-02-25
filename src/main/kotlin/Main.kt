package org.example

import io.github.cdimascio.dotenv.dotenv

fun main(args: Array<String>) {
    val apiKey = dotenv()["ANTHROPIC_API_KEY"] ?: error("Задайте ANTHROPIC_API_KEY в .env")

    if (args.firstOrNull() == "--demo") {
        DemoAgent(apiKey).run()
    } else {
        CliAgent(apiKey).run()
    }
}
