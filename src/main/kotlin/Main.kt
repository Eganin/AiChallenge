package org.example

import io.github.cdimascio.dotenv.dotenv
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun main() {
    val dotenv = dotenv()
    val apiKey = dotenv["ANTHROPIC_API_KEY"]
        ?: error("Set ANTHROPIC_API_KEY in .env file")

    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val body = JSONObject().apply {
        put("model", "claude-sonnet-4-5-20250929")
        put("max_tokens", 1024)
        put(
            "system",
            "Веди себя как Джонатан Джостар из аниме ДжоДжо. Отвечай в его стиле — благородно, вежливо и с джентльменским духом. Используй характерные фразы и манеру речи Джонатана."
        )
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Поприветствуй пользователя")
            })
        })
    }


    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .addHeader("content-type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            println("Error ${response.code}: ${response.body?.string()}")
            return
        }

        val json = JSONObject(response.body!!.string())
        val text = json.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")

        println(text)
    }
}
