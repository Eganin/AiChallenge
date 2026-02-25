package org.example

data class Message(val role: String, val content: String)

data class TokenUsage(val inputTokens: Int, val outputTokens: Int)

data class ClaudeResponse(val text: String, val usage: TokenUsage)
