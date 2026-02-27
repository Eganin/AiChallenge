package org.example

enum class StrategyType { SUMMARY, SLIDING_WINDOW, STICKY_FACTS, BRANCHING }

data class StrategyInfo(
    val type: StrategyType,
    val totalMessages: Int,
    val sentMessages: Int,
    val extra: Map<String, String> = emptyMap()
)

interface ContextStrategy {
    val type: StrategyType
    val fullHistory: List<Message>
    fun onUserMessage(msg: Message)
    fun onAssistantMessage(msg: Message)
    fun buildContextMessages(): List<Message>
    fun reset()
    fun getStrategyInfo(): StrategyInfo
}
