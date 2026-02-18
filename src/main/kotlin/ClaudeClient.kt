package org.example

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ClaudeClient(private val apiKey: String, val model: String) {

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun ask(userMessage: String, systemPrompt: String? = null, maxTokens: Int = 2048): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (systemPrompt != null) put("system", systemPrompt.trim())
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

        return httpClient.newCall(request).execute().use { response ->
            val raw = response.body!!.string()
            if (!response.isSuccessful) error("HTTP ${response.code}: $raw")
            JSONObject(raw).getJSONArray("content").getJSONObject(0).getString("text")
        }
    }
}
