package org.example

import io.github.cdimascio.dotenv.dotenv

// Anthropic models: weak / medium / strong
private const val HAIKU_MODEL = "claude-haiku-4-5-20251001"
private const val SONNET_MODEL = "claude-sonnet-4-6"
private const val OPUS_MODEL = "claude-opus-4-6"

fun main() {
    val apiKey = dotenv()["ANTHROPIC_API_KEY"] ?: error("Задайте ANTHROPIC_API_KEY в src/.env")

    PricingProvider.load()

    val question = Prompts.DESIGN_PATTERN_QUESTION
    Printer.modelBenchmarkHeader(question, PricingProvider.loadedFrom)

    val haiku = ClaudeClient(apiKey, HAIKU_MODEL)
    val sonnet = ClaudeClient(apiKey, SONNET_MODEL)
    val opus = ClaudeClient(apiKey, OPUS_MODEL)

    // ── Запрос #1 — слабая модель (Haiku) ────────────────────────────────
    println("\n  Отправляем запрос к слабой модели ($HAIKU_MODEL)...")
    val weakResult = haiku.ask(question)
    val weakCost = PricingProvider.calculateCost(HAIKU_MODEL, weakResult.inputTokens, weakResult.outputTokens)
    Printer.modelResult(1, "слабая", HAIKU_MODEL, weakResult, weakCost)

    // ── Запрос #2 — средняя модель (Sonnet) ───────────────────────────────
    println("\n  Отправляем запрос к средней модели ($SONNET_MODEL)...")
    val mediumResult = sonnet.ask(question)
    val mediumCost = PricingProvider.calculateCost(SONNET_MODEL, mediumResult.inputTokens, mediumResult.outputTokens)
    Printer.modelResult(2, "средняя", SONNET_MODEL, mediumResult, mediumCost)

    // ── Запрос #3 — сильная модель (Opus) ────────────────────────────────
    println("\n  Отправляем запрос к сильной модели ($OPUS_MODEL)...")
    val strongResult = opus.ask(question)
    val strongCost = PricingProvider.calculateCost(OPUS_MODEL, strongResult.inputTokens, strongResult.outputTokens)
    Printer.modelResult(3, "сильная", OPUS_MODEL, strongResult, strongCost)

    // ── Сравнение через API (Sonnet) ──────────────────────────────────────
    println("\n  Отправляем все три ответа на сравнение (через $SONNET_MODEL)...")
    val comparisonPrompt = Prompts.modelCompare(
        HAIKU_MODEL, weakResult.text, weakResult.totalTokens, weakResult.durationMs, weakCost,
        SONNET_MODEL, mediumResult.text, mediumResult.totalTokens, mediumResult.durationMs, mediumCost,
        OPUS_MODEL, strongResult.text, strongResult.totalTokens, strongResult.durationMs, strongCost
    )
    val comparison = sonnet.ask(comparisonPrompt, maxTokens = 2048)
    Printer.modelComparison(comparison.text)
}
