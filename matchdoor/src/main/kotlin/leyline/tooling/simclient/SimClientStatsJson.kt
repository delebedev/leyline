package leyline.tooling.simclient

fun statsToJson(
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
        if (row is DeckSimClientRow) {
            append("\"deckOverlay\":${overlayToJson(row.overlay)},")
            append("\"opponentDeckOverlay\":${overlayToJson(row.opponentOverlay)},")
        }
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

fun failureClass(stats: GameStats): String =
    when {
        stats.completionReason == "exception" -> "exception"
        stats.completionReason == "wall-timeout" -> "wall-timeout"
        stats.validationViolationsByCheck.isNotEmpty() -> "validation"
        stats.errorsByType.isNotEmpty() -> "log-error"
        stats.promptRouteFindings.any { it.bucket != PromptRouteAuditor.SAME_GRE_ROUTE_UNVERIFIED } -> "prompt-route"
        stats.completionReason in unresolvedCompletionReasons -> stats.completionReason
        else -> "natural"
    }

private val unresolvedCompletionReasons =
    setOf("turn-stall", "no-progress", "iter-cap", "max-turns", "cleanup")

fun simJsonString(s: String): String =
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

fun mapToJson(m: Map<String, Int>): String =
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

private fun overlayToJson(report: DeckOverlayReport?): String {
    if (report == null) return "null"
    return buildString {
        append('{')
        append("\"policy\":${simJsonString(if (report.policy == SimClientExcludePolicy.ReplaceBasic) "replace-basic" else "skip-deck")},")
        append("\"removedCount\":${report.removedCount},")
        append("\"removedCards\":${report.removedCards},")
        append("\"replacement\":${report.replacement?.let(::simJsonString) ?: "null"},")
        append("\"removed\":")
        append(
            report.removed.joinToString(",", "[", "]") { removal ->
                buildString {
                    append('{')
                    append("\"name\":${simJsonString(removal.name)},")
                    append("\"count\":${removal.count},")
                    append("\"grpId\":${removal.grpId ?: "null"},")
                    append("\"matchedBy\":${simJsonString(removal.matchedBy)}")
                    append('}')
                }
            },
        )
        append('}')
    }
}

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
