package org.example

import io.github.cdimascio.dotenv.dotenv

private const val MODEL = "claude-haiku-4-5-20251001"

fun main() {
    val apiKey = dotenv()["ANTHROPIC_API_KEY"] ?: error("Задайте ANTHROPIC_API_KEY в src/.env")
    val claude = ClaudeClient(apiKey, MODEL)

    Printer.header(Prompts.QUESTION, MODEL)

    val a1 = claude.ask(Prompts.QUESTION)
    Printer.section(1, "БАЗОВЫЙ", "Вопрос отправлен без каких-либо инструкций — чистый запрос", a1)

    val a2 = claude.ask(Prompts.QUESTION, systemPrompt = Prompts.STEP_BY_STEP_SYSTEM)
    Printer.section(2, "ПОШАГОВЫЙ", "Системный промпт: \"Решай пошагово\" — форсирует структурное мышление", a2)

    val a3 = claude.ask(Prompts.OPTIMIZED)
    Printer.section(3, "ОПТИМИЗИРОВАННЫЙ ПРОМПТ", "Роль эксперта + 10 структурных блоков + требование конкретных цен", a3)

    val a4 = claude.ask(Prompts.EXPERTS, maxTokens = 4096)
    Printer.section(4, "ГРУППА ЭКСПЕРТОВ", "Аналитик + Инженер-механик + Эндуро гонщик — каждый со своей стороны", a4)

    val a5 = claude.ask(Prompts.compare(a1, a2, a3, a4), maxTokens = 2048)
    Printer.comparison(a5)
}
