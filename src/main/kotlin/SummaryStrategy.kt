package org.example

class SummaryStrategy(
    client: ClaudeClient,
    tailSize: Int = 10,
    summaryEvery: Int = 10
) : ContextStrategy {

    val contextManager = ContextManager(client, tailSize, summaryEvery)

    override val type = StrategyType.SUMMARY
    override val fullHistory: List<Message> get() = contextManager.fullHistory

    val summary: String? get() = contextManager.summary
    val summarizedUpTo: Int get() = contextManager.summarizedUpTo
    val summaryUsage: TokenUsage get() = contextManager.summaryUsage
    val tailSize: Int get() = contextManager.tailSize

    override fun onUserMessage(msg: Message) = contextManager.addMessage(msg)
    override fun onAssistantMessage(msg: Message) = contextManager.addMessage(msg)
    override fun buildContextMessages() = contextManager.buildContextMessages()
    override fun reset() = contextManager.reset()

    override fun getStrategyInfo(): StrategyInfo {
        val sentCount = if (contextManager.tailSize > 0)
            minOf(contextManager.tailSize, contextManager.fullHistory.size)
        else contextManager.fullHistory.size
        return StrategyInfo(
            type = StrategyType.SUMMARY,
            totalMessages = contextManager.fullHistory.size,
            sentMessages = sentCount,
            extra = buildMap {
                if (contextManager.summary != null)
                    put("summary", "${contextManager.summarizedUpTo} сообщ. сжато")
                if (contextManager.summaryUsage.inputTokens > 0)
                    put("summaryTokens", "${contextManager.summaryUsage.inputTokens}/${contextManager.summaryUsage.outputTokens}")
            }
        )
    }

    fun restoreState(messages: List<Message>, summary: String?, summarizedUpTo: Int) =
        contextManager.restoreState(messages, summary, summarizedUpTo)
}
