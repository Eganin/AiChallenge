package org.example

object Printer {

    private const val WIDTH = 70
    private val SEP = "═".repeat(WIDTH)
    private val LINE = "─".repeat(WIDTH)

    fun temperatureHeader(question: String, model: String) {
        println(SEP)
        println("  AI CHALLENGE — Temperature эксперимент")
        println("  Вопрос: $question")
        println("  Модель: $model")
        println("  Тестируем temperature: 0.0 | 0.7 | 1.2")
        println(SEP)
    }

    fun temperatureSection(num: Int, temperature: Double, text: String?, error: String?) {
        println("\n$SEP")
        println("  ЗАПРОС #$num | temperature = $temperature")
        if (temperature > 1.0) {
            println("  ⚠  ВНИМАНИЕ: $temperature выходит за допустимый диапазон [0.0; 1.0]")
        }
        println(SEP)
        if (error != null) {
            println("  [ОШИБКА API] $error")
        } else {
            println(text ?: "")
        }
        println(LINE)
    }

    fun temperatureComparison(text: String) {
        println("\n$SEP")
        println("  СРАВНИТЕЛЬНЫЙ АНАЛИЗ ПО TEMPERATURE (от Claude API)")
        println(SEP)
        println(text)
        println(LINE)
    }
}
