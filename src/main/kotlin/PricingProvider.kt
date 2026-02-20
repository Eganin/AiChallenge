package org.example

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ModelPricing(
    val inputCostPerToken: Double,
    val outputCostPerToken: Double,
    val source: String
) {
    fun calculate(inputTokens: Int, outputTokens: Int): Double =
        inputTokens * inputCostPerToken + outputTokens * outputCostPerToken
}

object PricingProvider {

    private const val LITELLM_URL =
        "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"

    // Fallback: цены за токен (= за 1М / 1_000_000)
    private val FALLBACK = mapOf(
        "claude-haiku-4-5-20251001" to ModelPricing(0.80 / 1_000_000, 4.00 / 1_000_000, "fallback"),
        "claude-sonnet-4-6" to ModelPricing(3.00 / 1_000_000, 15.00 / 1_000_000, "fallback"),
        "claude-opus-4-6" to ModelPricing(15.00 / 1_000_000, 75.00 / 1_000_000, "fallback")
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    var loadedFrom: String = "не загружено"
        private set

    private var cache: Map<String, ModelPricing>? = null

    fun load(): Map<String, ModelPricing> {
        cache?.let { return it }
        return try {
            val request = Request.Builder().url(LITELLM_URL).build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body!!.string()
            }
            val root = JSONObject(body)
            val result = mutableMapOf<String, ModelPricing>()
            for (key in root.keys()) {
                val obj = root.optJSONObject(key) ?: continue
                val input = obj.optDouble("input_cost_per_token", Double.NaN)
                val output = obj.optDouble("output_cost_per_token", Double.NaN)
                if (!input.isNaN() && !output.isNaN()) {
                    result[key] = ModelPricing(input, output, "LiteLLM")
                }
            }
            loadedFrom = "LiteLLM (${result.size} моделей)"
            result.also { cache = it }
        } catch (e: Exception) {
            loadedFrom = "fallback (ошибка: ${e.message})"
            FALLBACK.also { cache = it }
        }
    }

    fun getPricing(model: String): ModelPricing =
        (cache ?: load())[model]
            ?: FALLBACK[model]
            ?: error("Нет цены для модели: $model")

    fun calculateCost(model: String, inputTokens: Int, outputTokens: Int): Double =
        getPricing(model).calculate(inputTokens, outputTokens)
}
