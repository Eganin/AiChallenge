package org.example

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ModelPricing(
    val inputCostPerToken: Double,
    val outputCostPerToken: Double
)

object PricingProvider {

    private const val LITELLM_URL =
        "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"

    private val FALLBACK = mapOf(
        "claude-haiku-4-5"  to ModelPricing(1.00 / 1_000_000,  5.00 / 1_000_000),
        "claude-3-5-haiku"  to ModelPricing(0.80 / 1_000_000,  4.00 / 1_000_000),
        "claude-3-haiku"    to ModelPricing(0.25 / 1_000_000,  1.25 / 1_000_000),
        "claude-sonnet-4"   to ModelPricing(3.00 / 1_000_000, 15.00 / 1_000_000),
        "claude-3-5-sonnet" to ModelPricing(3.00 / 1_000_000, 15.00 / 1_000_000),
        "claude-opus-4-6"   to ModelPricing(5.00 / 1_000_000, 25.00 / 1_000_000),
        "claude-opus-4-5"   to ModelPricing(5.00 / 1_000_000, 25.00 / 1_000_000),
        "claude-opus-4-1"   to ModelPricing(15.0 / 1_000_000, 75.00 / 1_000_000),
        "claude-opus-4"     to ModelPricing(15.0 / 1_000_000, 75.00 / 1_000_000),
        "claude-3-opus"     to ModelPricing(15.0 / 1_000_000, 75.00 / 1_000_000),
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var cache: Map<String, ModelPricing>? = null

    private fun load(): Map<String, ModelPricing> {
        return try {
            val request = Request.Builder().url(LITELLM_URL).build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string() ?: error("Empty response body")
            }
            val root = JSONObject(body)
            val result = mutableMapOf<String, ModelPricing>()
            for (key in root.keys()) {
                val obj = root.optJSONObject(key) ?: continue
                val input = obj.optDouble("input_cost_per_token", Double.NaN)
                val output = obj.optDouble("output_cost_per_token", Double.NaN)
                if (!input.isNaN() && !output.isNaN()) {
                    result[key] = ModelPricing(input, output)
                }
            }
            result
        } catch (e: Exception) {
            System.err.println("PricingProvider: failed to load from network (${e.message}), using fallback")
            FALLBACK.toMutableMap()
        }
    }

    private fun getCache(): Map<String, ModelPricing> {
        return cache ?: synchronized(this) {
            cache ?: load().also { cache = it }
        }
    }

    fun getPricing(model: String): ModelPricing? {
        val c = getCache()
        return c[model]
            ?: FALLBACK.entries.firstOrNull { (key, _) -> model.contains(key) }?.value
    }

    fun costUsd(model: String, usage: TokenUsage): Double? {
        val pricing = getPricing(model) ?: return null
        return usage.inputTokens * pricing.inputCostPerToken +
               usage.outputTokens * pricing.outputCostPerToken
    }

    fun formatCost(usd: Double): String = when {
        usd < 0.001 -> "$" + "%.6f".format(usd).replace(',', '.')
        usd < 1.0   -> "$" + "%.4f".format(usd).replace(',', '.')
        else        -> "$" + "%.2f".format(usd).replace(',', '.')
    }

    /** Visible for testing: reset cache to force reload */
    internal fun resetCache() { cache = null }
}
