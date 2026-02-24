// src/test/kotlin/ClaudeClientTest.kt
package org.example

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeClientTest {

    private fun buildRequestBody(messages: List<Message>, systemPrompt: String?): JSONObject {
        return JSONObject().apply {
            put("model", "test-model")
            put("max_tokens", 2048)
            if (systemPrompt != null) put("system", systemPrompt.trim())
            val arr = JSONArray()
            messages.forEach { msg ->
                arr.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            put("messages", arr)
        }
    }

    @Test
    fun `request body contains all messages in order`() {
        val messages = listOf(
            Message("user", "hello"),
            Message("assistant", "hi"),
            Message("user", "how are you?")
        )
        val body = buildRequestBody(messages, null)
        val arr = body.getJSONArray("messages")
        assertEquals(3, arr.length())
        assertEquals("user", arr.getJSONObject(0).getString("role"))
        assertEquals("hello", arr.getJSONObject(0).getString("content"))
        assertEquals("assistant", arr.getJSONObject(1).getString("role"))
        assertEquals("user", arr.getJSONObject(2).getString("role"))
        assertEquals("how are you?", arr.getJSONObject(2).getString("content"))
    }

    @Test
    fun `request body includes system prompt when provided`() {
        val messages = listOf(Message("user", "hello"))
        val body = buildRequestBody(messages, "You are a helpful assistant")
        assertEquals("You are a helpful assistant", body.getString("system"))
    }

    @Test
    fun `request body omits system key when systemPrompt is null`() {
        val messages = listOf(Message("user", "hello"))
        val body = buildRequestBody(messages, null)
        assertEquals(false, body.has("system"))
    }
}
