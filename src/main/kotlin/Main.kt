package org.example

import io.github.cdimascio.dotenv.dotenv

private const val MODEL = "claude-haiku-4-5-20251001"

fun main() {
    val apiKey = dotenv()["ANTHROPIC_API_KEY"] ?: error("Задайте ANTHROPIC_API_KEY в src/.env")
    val claude = ClaudeClient(apiKey, MODEL)

    Printer.temperatureHeader(Prompts.WAIFU_QUESTION, MODEL)

    fun safeAsk(temperature: Double): Pair<String?, String?> = try {
        claude.ask(Prompts.WAIFU_QUESTION, temperature = temperature) to null
    } catch (e: Exception) {
        null to (e.message ?: "Неизвестная ошибка")
    }

    val (r0, e0) = safeAsk(0.0)
    Printer.temperatureSection(1, 0.0, r0, e0)

    val (r07, e07) = safeAsk(0.7)
    Printer.temperatureSection(2, 0.7, r07, e07)

    // temperature=1.2 выходит за диапазон [0.0; 1.0] — API вернёт ошибку 422
    val (r12, e12) = safeAsk(1.2)
    Printer.temperatureSection(3, 1.2, r12, e12)

    val comparison = claude.ask(
        Prompts.temperatureCompare(
            r0 ?: "[ОШИБКА API: $e0]",
            r07 ?: "[ОШИБКА API: $e07]",
            r12 ?: "[ОШИБКА API: $e12]"
        ),
        maxTokens = 2048
    )
    Printer.temperatureComparison(comparison)
}
