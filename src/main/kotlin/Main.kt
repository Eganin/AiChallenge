package org.example

import io.github.cdimascio.dotenv.dotenv
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val MODEL = "claude-haiku-4-5-20251001"
private const val USER_PROMPT = "Поприветствуй пользователя и расскажи о своих ценностях"
private const val STOP_WORD = "отстаивать"

private const val SYSTEM_BASE = """
Веди себя как Джонатан Джостар из аниме ДжоДжо.
Отвечай в его стиле — благородно, вежливо и с джентльменским духом.
"""

private val SYSTEM_WITH_FORMAT = """
$SYSTEM_BASE
ФОРМАТ ОТВЕТА (строго соблюдай):
1. Приветствие — одно предложение.
2. Ценность №1 — одно предложение.
3. Ценность №2 — одно предложение.
Завершай ответ словом «$STOP_WORD».
""".trimIndent()

private val client = OkHttpClient.Builder()
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

fun ask(apiKey: String, systemPrompt: String, maxTokens: Int, stopSequences: List<String>? = null): JSONObject {
    val body = JSONObject().apply {
        put("model", MODEL)
        put("max_tokens", maxTokens)
        put("system", systemPrompt.trim())
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", USER_PROMPT)
        }))
        if (!stopSequences.isNullOrEmpty()) put("stop_sequences", JSONArray(stopSequences))
    }
    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .addHeader("content-type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    return client.newCall(request).execute().use { response ->
        val raw = response.body!!.string()
        if (!response.isSuccessful) error("HTTP ${response.code}: $raw")
        JSONObject(raw)
    }
}

fun printResult(label: String, maxTokens: Int, stopSequences: List<String>?, json: JSONObject, elapsedMs: Long) {
    val usage = json.getJSONObject("usage")
    val text = json.getJSONArray("content").getJSONObject(0).getString("text")

    println("\n${"=".repeat(60)}")
    println("  $label")
    println("=".repeat(60))
    println("  model          : $MODEL")
    println("  max_tokens     : $maxTokens")
    println("  stop_sequences : ${stopSequences?.joinToString() ?: "нет"}")
    println("  stop_reason    : ${json.getString("stop_reason")}")
    println("  tokens in/out  : ${usage.getInt("input_tokens")} / ${usage.getInt("output_tokens")}")
    println("  время          : ${elapsedMs} мс")
    println("-".repeat(60))
    println(text)
    println("-".repeat(60))
    println("  символов: ${text.length}  |  слов: ${text.split(" ").size}")
}

fun main() {
    val apiKey = dotenv()["ANTHROPIC_API_KEY"] ?: error("Задайте ANTHROPIC_API_KEY в src/.env")

    println("AI CHALLENGE 2 — сравнение запросов с разным контролем ответа")
    println("Модель: $MODEL\n")

    // Запрос 1: без ограничений
    var t = System.currentTimeMillis()
    val r1 = ask(apiKey, SYSTEM_BASE, maxTokens = 1024)
    printResult("БЕЗ ограничений", 1024, null, r1, System.currentTimeMillis() - t)

    // Запрос 2: с форматом, лимитом токенов и stop-sequence
    t = System.currentTimeMillis()
    val r2 = ask(apiKey, SYSTEM_WITH_FORMAT, maxTokens = 300, stopSequences = listOf(STOP_WORD))
    printResult(
        "С ограничениями  (формат + max_tokens=300 + stop=\"$STOP_WORD\")",
        300,
        listOf(STOP_WORD),
        r2,
        System.currentTimeMillis() - t
    )
}
