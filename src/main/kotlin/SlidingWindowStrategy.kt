package org.example

class SlidingWindowStrategy(val windowSize: Int = 10) : ContextStrategy {
    private val _history = mutableListOf<Message>()

    override val type = StrategyType.SLIDING_WINDOW
    override val fullHistory: List<Message> get() = _history.toList()

    override fun onUserMessage(msg: Message) { _history.add(msg) }
    override fun onAssistantMessage(msg: Message) { _history.add(msg) }

    override fun buildContextMessages(): List<Message> =
        if (windowSize > 0) _history.takeLast(windowSize) else _history.toList()

    override fun reset() { _history.clear() }

    override fun getStrategyInfo() = StrategyInfo(
        type = StrategyType.SLIDING_WINDOW,
        totalMessages = _history.size,
        sentMessages = if (windowSize > 0) minOf(windowSize, _history.size) else _history.size
    )

    fun restoreState(messages: List<Message>) {
        _history.clear()
        _history.addAll(messages)
    }
}
