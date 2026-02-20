package org.example

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiResponse(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

class ClaudeClient(private val apiKey: String, val model: String) {

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun ask(
        userMessage: String,
        systemPrompt: String? = null,
        maxTokens: Int = 2048,
        temperature: Double? = null
    ): AiResponse {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (systemPrompt != null) put("system", systemPrompt.trim())
            if (temperature != null) put("temperature", temperature)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            }))
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val startTime = System.currentTimeMillis()
        return httpClient.newCall(request).execute().use { response ->
            val raw = response.body!!.string()
            val durationMs = System.currentTimeMillis() - startTime
            if (!response.isSuccessful) error("HTTP ${response.code}: $raw")
            val json = JSONObject(raw)
            val text = json.getJSONArray("content").getJSONObject(0).getString("text")
            val usage = json.getJSONObject("usage")
            val inputTokens = usage.getInt("input_tokens")
            val outputTokens = usage.getInt("output_tokens")
            AiResponse(text, inputTokens, outputTokens, durationMs)
        }
    }
}
