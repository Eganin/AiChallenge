package org.example

class BranchRecord(
    var id: Long,
    val name: String,
    val checkpointPosition: Int,
    val messages: MutableList<Message> = mutableListOf(),
    var savedMessageCount: Int = 0
)

class BranchingStrategy : ContextStrategy {
    private val _trunkMessages = mutableListOf<Message>()
    private val _branches = mutableListOf<BranchRecord>()
    private var _activeBranchIndex: Int = -1

    override val type = StrategyType.BRANCHING

    val trunkMessages: List<Message> get() = _trunkMessages.toList()
    val branches: List<BranchRecord> get() = _branches.toList()
    val activeBranch: BranchRecord? get() = if (_activeBranchIndex >= 0) _branches[_activeBranchIndex] else null
    val isOnTrunk: Boolean get() = _activeBranchIndex < 0
    var trunkSavedCount: Int = 0

    override val fullHistory: List<Message>
        get() {
            val ab = activeBranch
            return if (ab != null) _trunkMessages.take(ab.checkpointPosition) + ab.messages
            else _trunkMessages.toList()
        }

    override fun onUserMessage(msg: Message) {
        activeBranch?.messages?.add(msg) ?: _trunkMessages.add(msg)
    }

    override fun onAssistantMessage(msg: Message) {
        activeBranch?.messages?.add(msg) ?: _trunkMessages.add(msg)
    }

    override fun buildContextMessages(): List<Message> = fullHistory.toList()

    override fun reset() {
        _trunkMessages.clear()
        _branches.clear()
        _activeBranchIndex = -1
        trunkSavedCount = 0
    }

    override fun getStrategyInfo() = StrategyInfo(
        type = StrategyType.BRANCHING,
        totalMessages = fullHistory.size,
        sentMessages = fullHistory.size,
        extra = mapOf(
            "ветка" to (activeBranch?.name ?: "trunk"),
            "веток" to _branches.size.toString()
        )
    )

    fun createBranch(name: String): BranchRecord {
        require(name != "trunk") { "«trunk» — зарезервированное имя" }
        require(_branches.none { it.name == name }) { "Ветка «$name» уже существует" }
        val checkpoint = _trunkMessages.size
        val branch = BranchRecord(id = -1L, name = name, checkpointPosition = checkpoint)
        _branches.add(branch)
        _activeBranchIndex = _branches.size - 1
        return branch
    }

    fun switchTo(name: String): Boolean {
        if (name == "trunk") { _activeBranchIndex = -1; return true }
        val idx = _branches.indexOfFirst { it.name == name }
        if (idx < 0) return false
        _activeBranchIndex = idx
        return true
    }

    fun updateBranchId(name: String, id: Long) {
        _branches.firstOrNull { it.name == name }?.id = id
    }

    fun restoreState(trunkMessages: List<Message>, branches: List<BranchRecord>, activeBranchId: Long?) {
        _trunkMessages.clear()
        _trunkMessages.addAll(trunkMessages)
        _branches.clear()
        _branches.addAll(branches)
        trunkSavedCount = trunkMessages.size
        _activeBranchIndex = if (activeBranchId != null)
            _branches.indexOfFirst { it.id == activeBranchId }
        else -1
    }
}
