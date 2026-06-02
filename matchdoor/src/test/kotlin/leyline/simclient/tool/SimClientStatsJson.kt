package leyline.simclient.tool

internal val builtinDecks: Map<String, String> =
    mapOf(
        "forest-only" to "60 Forest",
        "bears" to "24 Forest\n36 Grizzly Bears",
        "mono-g-curve" to "24 Forest\n18 Grizzly Bears\n18 Centaur Courser",
        "mono-r-burn" to
            "20 Mountain\n4 Lightning Bolt\n4 Shock\n4 Burst Lightning\n" +
            "4 Fiery Temper\n4 Lava Axe\n4 Raging Goblin\n4 Goblin Fireslinger\n" +
            "4 Hurloon Minotaur\n4 Crackling Cyclops\n4 Monastery Swiftspear",
        "etb-triggers" to
            "24 Plains\n4 Reigning Victor\n4 Dalkovan Packbeasts\n" +
            "4 Furious Forebear\n4 Stormchaser's Talent\n" +
            "12 Savannah Lions\n4 Wall of Omens\n4 Soul Warden",
        "kicker" to
            "24 Forest\n4 Gnarlid Colony\n4 Territorial Allosaurus\n" +
            "4 Cragplate Baloth\n4 Inscription of Abundance\n" +
            "4 Llanowar Elves\n8 Grizzly Bears\n8 Centaur Courser",
    )

internal fun statsToJson(
    row: SimClientRow,
    stats: GameStats,
    policy: SimClientPolicyMode,
): String {
    val histo = stats.promptHistogram.entries.joinToString(",", "{", "}") { (k, v) -> "${simJsonString(k.name)}:$v" }
    return buildString {
        append('{')
        append("\"schemaVersion\":$STATS_SCHEMA_VERSION,")
        append("\"deck\":${simJsonString(row.runLabel)},")
        row.opponentRunLabel?.let { append("\"opponentDeck\":${simJsonString(it)},") }
        append("\"runKind\":${simJsonString(row.runKind)},")
        append("\"seed\":${row.seed},")
        append("\"policy\":${simJsonString(if (policy == SimClientPolicyMode.ForgeAi) "forge-ai" else "greedy")},")
        append("\"failureClass\":${simJsonString(failureClass(stats))},")
        append("\"durationMs\":${stats.durationMs},")
        append("\"turn\":${stats.turn},")
        append("\"gameOver\":${stats.gameOver},")
        append("\"winnerSeat\":${stats.winnerSeat ?: "null"},")
        append("\"loserSeat\":${stats.loserSeat ?: "null"},")
        append("\"finalLifeBySeat\":${mapToJson(stats.finalLifeBySeat)},")
        append("\"finalStatusBySeat\":${stringMapToJson(stats.finalStatusBySeat)},")
        append("\"completionReason\":${simJsonString(stats.completionReason)},")
        append("\"cleanupConcede\":${stats.cleanupConcede},")
        append("\"iterations\":${stats.iterations},")
        append("\"totalMessages\":${stats.totalMessages},")
        append("\"hitIterCap\":${stats.hitIterCap},")
        append("\"aiConsulted\":${stats.aiConsulted},")
        append("\"aiChose\":${stats.aiChose},")
        append("\"aiConsultedByPrompt\":${mapToJson(stats.aiConsultedByPrompt)},")
        append("\"aiChoseByPrompt\":${mapToJson(stats.aiChoseByPrompt)},")
        append("\"aiTotalMs\":${stats.aiTotalMs},")
        append("\"aiTotalMsByPrompt\":${longMapToJson(stats.aiTotalMsByPrompt)},")
        append("\"aiMaxMsByPrompt\":${longMapToJson(stats.aiMaxMsByPrompt)},")
        append("\"targetChoiceCounts\":${mapToJson(stats.targetChoiceCounts)},")
        append("\"targetChoiceSamples\":${stringMapToJson(stats.targetChoiceSamples)},")
        append("\"promptHistogram\":$histo,")
        append("\"promptRequestsByKind\":${mapToJson(stats.promptRequestsByKind)},")
        append("\"promptRequestSamplesByKind\":${stringMapToJson(stats.promptRequestSamplesByKind)},")
        append("\"promptRouteFindings\":${routeFindingsToJson(stats.promptRouteFindings)},")
        append("\"simFindings\":${simFindingsToJson(stats.simFindings)},")
        append("\"promptProgressSamples\":${promptProgressToJson(stats.promptProgressSamples)},")
        append("\"warnsByLogger\":${mapToJson(stats.warnsByLogger)},")
        append("\"errorsByType\":${mapToJson(stats.errorsByType)},")
        append("\"logErrorSamples\":${stringsToJson(stats.logErrorSamples)},")
        append("\"validationViolationsByCheck\":${mapToJson(stats.validationViolationsByCheck)},")
        append("\"validationViolations\":${stringsToJson(stats.validationViolations)},")
        append("\"exceptionMessage\":${stats.exceptionMessage?.let(::simJsonString) ?: "null"},")
        append("\"exceptionStackTop\":${stats.exceptionStackTop?.let(::simJsonString) ?: "null"},")
        append("\"promptRetiredByReason\":${mapToJson(stats.promptRetiredByReason)},")
        append("\"decisionOutcomes\":${mapToJson(stats.decisionOutcomes)},")
        append("\"actionAttemptsByType\":${mapToJson(stats.actionAttemptsByType)},")
        append("\"noPendingByDecision\":${mapToJson(stats.noPendingByDecision)},")
        append("\"skippedAlreadyTried\":${stats.skippedAlreadyTried},")
        append("\"connectMs\":${stats.connectMs},")
        append("\"stepTotalMs\":${stats.stepTotalMs},")
        append("\"stepMaxMs\":${stats.stepMaxMs},")
        append("\"flushTotalMs\":${stats.flushTotalMs},")
        append("\"flushMaxMs\":${stats.flushMaxMs},")
        append("\"autoPassTotalMs\":${stats.autoPassTotalMs},")
        append("\"autoPassMaxMs\":${stats.autoPassMaxMs},")
        append("\"policyTotalMsByPrompt\":${longMapToJson(stats.policyTotalMsByPrompt)},")
        append("\"policyMaxMsByPrompt\":${longMapToJson(stats.policyMaxMsByPrompt)},")
        append("\"submitTotalMsByDecision\":${longMapToJson(stats.submitTotalMsByDecision)},")
        append("\"submitMaxMsByDecision\":${longMapToJson(stats.submitMaxMsByDecision)},")
        append("\"stalledPrompt\":${stats.stalledPrompt?.let(::simJsonString) ?: "null"},")
        append("\"stalledFingerprint\":${stats.stalledFingerprint?.let(::simJsonString) ?: "null"}")
        append('}')
    }
}

internal fun failureClass(stats: GameStats): String =
    when {
        stats.completionReason == "exception" -> "exception"
        stats.completionReason == "wall-timeout" -> "wall-timeout"
        stats.validationViolationsByCheck.isNotEmpty() -> "validation"
        stats.errorsByType.isNotEmpty() -> "log-error"
        stats.promptRouteFindings.isNotEmpty() -> "prompt-route"
        stats.completionReason in unresolvedCompletionReasons -> stats.completionReason
        else -> "natural"
    }

private val unresolvedCompletionReasons =
    setOf("turn-stall", "no-progress", "iter-cap", "max-turns", "cleanup")

internal fun fileSafeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "deck" }

internal fun simJsonString(s: String): String =
    buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '\\', '"' -> append('\\').append(c)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

internal fun mapToJson(m: Map<String, Int>): String =
    m.entries.joinToString(",", "{", "}") { (k, v) ->
        "${simJsonString(k)}:$v"
    }

private fun longMapToJson(m: Map<String, Long>): String =
    m.entries.joinToString(",", "{", "}") { (k, v) ->
        "${simJsonString(k)}:$v"
    }

private fun stringMapToJson(m: Map<String, String>): String =
    m.entries.joinToString(",", "{", "}") { (k, v) -> "${simJsonString(k)}:${simJsonString(v)}" }

private fun stringsToJson(values: List<String>): String = values.joinToString(",", "[", "]") { simJsonString(it) }

private fun intsToJson(values: List<Int>): String = values.joinToString(",", "[", "]")

private fun promptProgressToJson(values: List<PromptProgressSample>): String =
    values.joinToString(",", "[", "]") { sample ->
        buildString {
            append('{')
            append("\"promptType\":${simJsonString(sample.promptType)},")
            append("\"decisionKind\":${simJsonString(sample.decisionKind)},")
            append("\"submitResult\":${simJsonString(sample.submitResult)},")
            append("\"promptMsgId\":${sample.promptMsgId},")
            append("\"promptGameStateId\":${sample.promptGameStateId},")
            append("\"beforeMsgId\":${sample.beforeMsgId},")
            append("\"beforeGameStateId\":${sample.beforeGameStateId},")
            append("\"afterMsgId\":${sample.afterMsgId},")
            append("\"afterGameStateId\":${sample.afterGameStateId},")
            append("\"beforeMessages\":${sample.beforeMessages},")
            append("\"afterMessages\":${sample.afterMessages},")
            append("\"sourceInstanceId\":${sample.sourceInstanceId},")
            append("\"sourceGrpId\":${sample.sourceGrpId},")
            append("\"abilityGrpId\":${sample.abilityGrpId},")
            append("\"targetIds\":${intsToJson(sample.targetIds)},")
            append("\"sourceBefore\":${simJsonString(sample.sourceBefore)},")
            append("\"sourceAfter\":${simJsonString(sample.sourceAfter)}")
            append('}')
        }
    }

private fun routeFindingsToJson(values: List<PromptRouteFinding>): String =
    values.joinToString(",", "[", "]") { finding ->
        buildString {
            append('{')
            append("\"bucket\":${simJsonString(finding.bucket)},")
            append("\"routeKey\":${simJsonString(finding.routeKey)},")
            append("\"enginePromptType\":${simJsonString(finding.enginePromptType)},")
            append("\"semantic\":${simJsonString(finding.semantic)},")
            append("\"expectedGreType\":${simJsonString(finding.expectedGreType)},")
            append("\"expectedCount\":${finding.expectedCount},")
            append("\"emittedCount\":${finding.emittedCount},")
            append("\"outcomeCounts\":${mapToJson(finding.outcomeCounts)},")
            append("\"sampleMessage\":${simJsonString(finding.sampleMessage)}")
            append('}')
        }
    }

private fun simFindingsToJson(values: List<SimClientFinding>): String =
    values.joinToString(",", "[", "]") { finding ->
        buildString {
            append('{')
            append("\"kind\":${simJsonString(finding.kind)},")
            append("\"key\":${simJsonString(finding.key)},")
            append("\"count\":${finding.count},")
            append("\"sample\":${simJsonString(finding.sample)}")
            append('}')
        }
    }
