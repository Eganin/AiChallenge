package org.example

object Printer {

    private const val WIDTH = 70
    private val SEP = "═".repeat(WIDTH)
    private val LINE = "─".repeat(WIDTH)

    fun header(question: String, model: String) {
        println(SEP)
        println("  AI CHALLENGE 3 — 4 подхода к одному вопросу")
        println("  Вопрос: $question")
        println("  Модель: $model")
        println(SEP)
    }

    fun section(num: Int, typeName: String, description: String, text: String) {
        println("\n$SEP")
        println("  ЗАПРОС #$num | Тип: $typeName")
        println("  Описание: $description")
        println(SEP)
        println(text)
        println(LINE)
    }

    fun comparison(text: String) {
        println("\n$SEP")
        println("  СРАВНИТЕЛЬНЫЙ АНАЛИЗ ЧЕТЫРЁХ ПОДХОДОВ (от Claude API)")
        println(SEP)
        println(text)
        println(LINE)
    }
}
