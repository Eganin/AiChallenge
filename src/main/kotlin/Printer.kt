package org.example

object Printer {

    private const val WIDTH = 70
    private val SEP = "═".repeat(WIDTH)
    private val LINE = "─".repeat(WIDTH)

    // ── Model benchmark (Challenge 1.5) ──────────────────────────────────

    fun modelBenchmarkHeader(question: String, pricingSource: String) {
        println(SEP)
        println("  AI CHALLENGE — Сравнение моделей")
        println("  Вопрос: $question")
        println("  Модели: Haiku (слабая) | Sonnet (средняя) | Opus (сильная)")
        println("  Прайс:  $pricingSource")
        println(SEP)
    }

    fun modelResult(num: Int, tier: String, model: String, result: AiResponse, cost: Double) {
        println("\n$SEP")
        println("  ОТВЕТ #$num | $tier модель — $model")
        println(LINE)
        println("  Время:   ${result.durationMs} мс")
        println("  Токены:  input=${result.inputTokens} | output=${result.outputTokens} | total=${result.totalTokens}")
        println("  Стоимость: \$${"%.6f".format(cost)}")
        println(SEP)
        println(result.text)
        println(LINE)
    }

    fun modelComparison(text: String) {
        println("\n$SEP")
        println("  СРАВНИТЕЛЬНЫЙ АНАЛИЗ МОДЕЛЕЙ (от Claude API)")
        println(SEP)
        println(text)
        println(LINE)
    }
}
