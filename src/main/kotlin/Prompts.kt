package org.example

object Prompts {

    const val DESIGN_PATTERN_QUESTION = "Объясни паттерн проектирования команда на языке kotlin"

    fun modelCompare(
        weakModel: String, weakAnswer: String, weakTotalTokens: Int, weakTimeMs: Long, weakCost: Double,
        mediumModel: String, mediumAnswer: String, mediumTotalTokens: Int, mediumTimeMs: Long, mediumCost: Double,
        strongModel: String, strongAnswer: String, strongTotalTokens: Int, strongTimeMs: Long, strongCost: Double
    ) = """
        Тебе даны три ответа на вопрос: "$DESIGN_PATTERN_QUESTION"
        Ответы получены от трёх разных моделей Claude.

        ОТВЕТ #1 (слабая модель — $weakModel):
        Метрики: время=${weakTimeMs}мс | токены=$weakTotalTokens | стоимость=$${"%.6f".format(weakCost)}
        $weakAnswer

        ОТВЕТ #2 (средняя модель — $mediumModel):
        Метрики: время=${mediumTimeMs}мс | токены=$mediumTotalTokens | стоимость=$${"%.6f".format(mediumCost)}
        $mediumAnswer

        ОТВЕТ #3 (сильная модель — $strongModel):
        Метрики: время=${strongTimeMs}мс | токены=$strongTotalTokens | стоимость=$${"%.6f".format(strongCost)}
        $strongAnswer

        Проведи сравнительный анализ строго по трём критериям:

        1. КАЧЕСТВО ОТВЕТОВ: сравни полноту, точность, структурированность и глубину объяснения.
           Оцени каждый ответ по 10-балльной шкале с обоснованием.

        2. СКОРОСТЬ ОТВЕТА: проанализируй разницу во времени ответа между моделями.
           Объясни зависимость скорости от размера модели.

        3. РЕСУРСОЁМКОСТЬ: сравни токены и стоимость. Оцени соотношение цена/качество.
           Для каких задач оправдано использовать каждую модель?

        Структурируй ответ строго по этим трём пунктам с заголовками.
    """.trimIndent()
}
