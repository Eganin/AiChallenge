// src/main/kotlin/CliAgent.kt
package org.example

class CliAgent(apiKey: String, model: String = "claude-haiku-4-5-20251001") {

    private val client = ClaudeClient(apiKey, model)
    private val repository = SessionRepository()

    fun run() {
        println("CLI AI Agent | ${client.model}")
        val (record, history) = selectOrCreateSession()
        val session = buildSession(record, history)
        val strategyLabel = record.strategy.name.lowercase().replace('_', ' ')
        println("Стратегия: $strategyLabel")
        printHelp(record.strategy)

        var savedTrunkCount = history.size

        try {
            while (true) {
                print("Вы: ")
                val input = readlnOrNull()?.trim() ?: break
                when {
                    input.lowercase() == "exit" -> break
                    input == "/reset" -> {
                        handleReset(record, session)
                        savedTrunkCount = 0
                    }
                    input == "/delete" -> {
                        repository.deleteSession(record.id)
                        println("Сессия «${record.name}» удалена.\n")
                        break
                    }
                    input == "/facts" && record.strategy == StrategyType.STICKY_FACTS -> {
                        val sf = session.strategy as StickyFactsStrategy
                        if (sf.facts.isEmpty()) println("Фактов нет.\n")
                        else {
                            sf.facts.forEach { (k, v) -> println("  $k: $v") }
                            println()
                        }
                    }
                    input.startsWith("/forget ") && record.strategy == StrategyType.STICKY_FACTS -> {
                        val key = input.removePrefix("/forget ").trim()
                        val sf = session.strategy as StickyFactsStrategy
                        sf.removeFact(key)
                        repository.updateFacts(record.id, sf.factsJson)
                        println("Факт «$key» удалён.\n")
                    }
                    input.startsWith("/branch ") && record.strategy == StrategyType.BRANCHING -> {
                        val name = input.removePrefix("/branch ").trim()
                        val bs = session.strategy as BranchingStrategy
                        try {
                            val branch = bs.createBranch(name)
                            val branchId = repository.createBranch(record.id, name, branch.checkpointPosition)
                            bs.updateBranchId(name, branchId)
                            repository.updateActiveBranch(record.id, branchId)
                            println("Ветка «$name» создана от позиции ${branch.checkpointPosition}. Вы на ветке «$name».\n")
                        } catch (e: IllegalArgumentException) {
                            println("Ошибка: ${e.message}\n")
                        }
                    }
                    input.startsWith("/switch ") && record.strategy == StrategyType.BRANCHING -> {
                        val name = input.removePrefix("/switch ").trim()
                        val bs = session.strategy as BranchingStrategy
                        if (bs.switchTo(name)) {
                            val activeId = bs.activeBranch?.id
                            repository.updateActiveBranch(record.id, activeId)
                            println("Переключено на ветку «${bs.activeBranch?.name ?: "trunk"}».\n")
                        } else {
                            println("Ветка «$name» не найдена.\n")
                        }
                    }
                    input == "/branches" && record.strategy == StrategyType.BRANCHING -> {
                        val bs = session.strategy as BranchingStrategy
                        val activeName = bs.activeBranch?.name ?: "trunk"
                        val trunkMarker = if (bs.isOnTrunk) "* " else "  "
                        println("${trunkMarker}trunk (${bs.trunkMessages.size} сообщ.)")
                        bs.branches.forEach { b ->
                            val m = if (b.name == activeName) "* " else "  "
                            println("$m${b.name} (${b.messages.size} сообщ., от позиции ${b.checkpointPosition})")
                        }
                        println()
                    }
                    input.isEmpty() -> continue
                    else -> {
                        val reply = session.chat(input)
                        println("\nАгент: $reply\n")
                        saveAfterChat(session, record, savedTrunkCount)
                        // BRANCHING uses its own internal counters (trunkSavedCount / savedMessageCount)
                        if (record.strategy != StrategyType.BRANCHING) savedTrunkCount += 2
                        printStats(session)
                    }
                }
            }
        } finally {
            repository.close()
        }
    }

    private fun saveAfterChat(session: ConversationSession, record: SessionRecord, savedTrunkCount: Int) {
        when (record.strategy) {
            StrategyType.SUMMARY -> {
                val ss = session.strategy as SummaryStrategy
                repository.appendMessages(record.id, session.history.takeLast(2), savedTrunkCount)
                repository.updateSummaryState(record.id, ss.summary, ss.summarizedUpTo)
            }
            StrategyType.SLIDING_WINDOW -> {
                repository.appendMessages(record.id, session.history.takeLast(2), savedTrunkCount)
            }
            StrategyType.STICKY_FACTS -> {
                val sf = session.strategy as StickyFactsStrategy
                repository.appendMessages(record.id, session.history.takeLast(2), savedTrunkCount)
                repository.updateFacts(record.id, sf.factsJson)
            }
            StrategyType.BRANCHING -> {
                val bs = session.strategy as BranchingStrategy
                val ab = bs.activeBranch
                if (ab != null) {
                    repository.appendMessages(record.id, session.history.takeLast(2), ab.savedMessageCount, ab.id)
                    ab.savedMessageCount += 2
                    repository.updateActiveBranch(record.id, ab.id)
                } else {
                    repository.appendMessages(record.id, session.history.takeLast(2), bs.trunkSavedCount)
                    bs.trunkSavedCount += 2
                }
            }
        }
    }

    private fun buildSession(record: SessionRecord, history: List<Message>): ConversationSession {
        return when (record.strategy) {
            StrategyType.SUMMARY -> {
                val (summary, summarizedUpTo) = repository.loadSummaryState(record.id)
                val strategy = SummaryStrategy(client, tailSize = 10, summaryEvery = 10)
                strategy.restoreState(history, summary, summarizedUpTo)
                if (history.isNotEmpty()) {
                    val note = if (summary != null) ", summary: ${summarizedUpTo} сообщ." else ""
                    println("Загружена сессия «${record.name}» (${history.size / 2} обменов$note)\n")
                }
                ConversationSession(client, record.systemPrompt, strategy)
            }
            StrategyType.SLIDING_WINDOW -> {
                val strategy = SlidingWindowStrategy(windowSize = 10)
                strategy.restoreState(history)
                if (history.isNotEmpty()) println("Загружена сессия «${record.name}» (${history.size / 2} обменов)\n")
                ConversationSession(client, record.systemPrompt, strategy)
            }
            StrategyType.STICKY_FACTS -> {
                val strategy = StickyFactsStrategy(client, tailSize = 10)
                val factsJson = repository.loadFacts(record.id)
                strategy.restoreState(history, factsJson)
                if (history.isNotEmpty()) {
                    println("Загружена сессия «${record.name}» (${history.size / 2} обменов, ${strategy.facts.size} фактов)\n")
                }
                ConversationSession(client, record.systemPrompt, strategy)
            }
            StrategyType.BRANCHING -> {
                val strategy = BranchingStrategy()
                val trunkMsgs = repository.loadMessages(record.id, branchId = null)
                val branchInfos = repository.loadBranches(record.id)
                val activeBranchId = repository.loadActiveBranchId(record.id)
                val branches = branchInfos.map { bi ->
                    val msgs = repository.loadMessages(record.id, branchId = bi.id)
                    BranchRecord(bi.id, bi.name, bi.checkpoint, msgs.toMutableList(), savedMessageCount = msgs.size)
                }
                strategy.restoreState(trunkMsgs, branches, activeBranchId)
                if (trunkMsgs.isNotEmpty() || branches.isNotEmpty()) {
                    val branchLabel = strategy.activeBranch?.name ?: "trunk"
                    println("Загружена сессия «${record.name}» (${branches.size} веток, активна: $branchLabel)\n")
                }
                ConversationSession(client, record.systemPrompt, strategy)
            }
        }
    }

    private fun handleReset(record: SessionRecord, session: ConversationSession) {
        repository.clearMessages(record.id)
        session.reset()
        println("История очищена.\n")
    }

    private fun printHelp(strategy: StrategyType) {
        val base = "exit, /reset, /delete"
        val extra = when (strategy) {
            StrategyType.STICKY_FACTS -> ", /facts, /forget <ключ>"
            StrategyType.BRANCHING -> ", /branch <имя>, /switch <имя>, /branches"
            else -> ""
        }
        println("Команды: $base$extra\n")
    }

    private fun printStats(session: ConversationSession) {
        val last = session.lastUsage
        val total = session.sessionUsage
        val lastCost = PricingProvider.costUsd(client.model, last)
        val totalCost = PricingProvider.costUsd(client.model, total)
        val lastCostStr = lastCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
        val totalCostStr = totalCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
        println(
            "Токены — запрос: вход=${last.inputTokens}, выход=${last.outputTokens}$lastCostStr" +
            " | сессия: вход=${total.inputTokens}, выход=${total.outputTokens}$totalCostStr"
        )

        val contextMax = PricingProvider.getContextWindow(client.model)
        val usedPct = if (contextMax > 0) last.inputTokens * 100.0 / contextMax else 0.0
        val info = session.strategy.getStrategyInfo()
        val extraStr = info.extra.entries.joinToString(", ") { (k, v) -> "$k: $v" }
            .let { if (it.isNotEmpty()) " | $it" else "" }
        println(
            "Контекст: ${last.inputTokens}/$contextMax токенов " +
            "(${"%.1f".format(usedPct).replace(',', '.')}%) | " +
            "стратегия: ${info.type.name.lowercase()} | " +
            "отправлено: ${info.sentMessages}/${info.totalMessages} сообщ.$extraStr"
        )

        if (session.strategy is SummaryStrategy) {
            val sumUsage = (session.strategy as SummaryStrategy).summaryUsage
            if (sumUsage.inputTokens > 0) {
                val sumCost = PricingProvider.costUsd(client.model, sumUsage)
                val sumCostStr = sumCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
                println("Суммаризация (накоплено): вход=${sumUsage.inputTokens}, выход=${sumUsage.outputTokens}$sumCostStr")
            }
        }
        if (session.strategy is StickyFactsStrategy) {
            val factsUsage = (session.strategy as StickyFactsStrategy).factsUsage
            if (factsUsage.inputTokens > 0) {
                val factsCost = PricingProvider.costUsd(client.model, factsUsage)
                val factsCostStr = factsCost?.let { " (${PricingProvider.formatCost(it)})" } ?: ""
                println("Facts-токены (накоплено): вход=${factsUsage.inputTokens}, выход=${factsUsage.outputTokens}$factsCostStr")
            }
        }
        println()
    }

    private fun selectOrCreateSession(): Pair<SessionRecord, List<Message>> {
        val sessions = repository.listSessions()
        if (sessions.isEmpty()) {
            println("Сохранённых сессий нет. Создаём первую.\n")
            return createNewSession() to emptyList()
        }
        println("Сессии:")
        sessions.forEachIndexed { i, s ->
            println("  ${i + 1}. ${s.name} [${s.strategy.name.lowercase()}]")
        }
        println("  ${sessions.size + 1}. Новая сессия")
        println()
        while (true) {
            print("Выбор (1–${sessions.size + 1}): ")
            val choice = readlnOrNull()?.trim()?.toIntOrNull() ?: continue
            when {
                choice in 1..sessions.size -> {
                    val rec = sessions[choice - 1]
                    return rec to repository.loadMessages(rec.id)
                }
                choice == sessions.size + 1 -> return createNewSession() to emptyList()
                else -> println("Неверный выбор.\n")
            }
        }
    }

    private fun createNewSession(): SessionRecord {
        print("Имя сессии: ")
        val name = readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
        print("System prompt (Enter чтобы пропустить): ")
        val prompt = readlnOrNull()?.trim().takeIf { !it.isNullOrEmpty() }
        println()
        println("Стратегии управления контекстом:")
        println("  1. Summary        — суммаризация + хвост сообщений")
        println("  2. Sliding Window — последние N сообщений")
        println("  3. Sticky Facts   — ключевые факты + хвост")
        println("  4. Branching      — ветки диалога")
        val strategy = readStrategyChoice()
        println()
        return repository.createSession(name, prompt, strategy)
    }

    private fun readStrategyChoice(): StrategyType {
        while (true) {
            print("Выбор стратегии (1-4): ")
            return when (readlnOrNull()?.trim()) {
                "1" -> StrategyType.SUMMARY
                "2" -> StrategyType.SLIDING_WINDOW
                "3" -> StrategyType.STICKY_FACTS
                "4" -> StrategyType.BRANCHING
                else -> { println("Неверный выбор."); continue }
            }
        }
    }
}
