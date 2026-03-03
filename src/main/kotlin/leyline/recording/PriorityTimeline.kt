package leyline.recording

import leyline.game.CardDb
import leyline.recording.RecordingDecoder.DecodedMessage

/**
 * Priority-focused analysis of decoded recording messages.
 *
 * Extracts:
 * - Priority grant/skip timeline (AARs with phase context)
 * - Client auto-pass configuration changes
 * - shouldStop distribution across action types
 * - autoPassPriority patterns
 */
object PriorityTimeline {

    data class PriorityGrant(
        val gsId: Int,
        val turn: Int,
        val phase: String,
        val step: String,
        val activePlayer: Int,
        val priorityPlayer: Int,
        val actionTypes: List<String>,
        val shouldStopActions: List<String>,
        val noStopActions: List<String>,
        val highlights: List<String>,
    )

    data class PriorityResponse(
        val gsId: Int,
        val actionType: String,
        val grpId: Int,
        val cardName: String?,
        val autoPassPriority: String?,
    )

    data class SettingsChange(
        val index: Int,
        val autoPassOption: String?,
        val stackAutoPassOption: String?,
        val smartStops: String?,
        val stopCount: Int,
        val stops: List<RecordingDecoder.StopSummary>,
        val transientStopCount: Int,
        val yieldCount: Int,
        val clearAllStops: Boolean,
        val clearAllYields: Boolean,
    )

    data class ShouldStopProfile(
        val actionType: String,
        val shouldStopTrue: Int,
        val shouldStopFalse: Int,
    )

    data class Analysis(
        val totalGSMs: Int,
        val totalAARs: Int,
        val grantRate: Double,
        val grants: List<PriorityGrant>,
        val responses: List<PriorityResponse>,
        val settingsChanges: List<SettingsChange>,
        val shouldStopProfile: List<ShouldStopProfile>,
        val autoPassDistribution: Map<String, Int>,
        val edictalCount: Int,
        val phaseDistribution: Map<String, Int>,
    )

    fun analyze(messages: List<DecodedMessage>): Analysis {
        val grants = mutableListOf<PriorityGrant>()
        val responses = mutableListOf<PriorityResponse>()
        val settingsChanges = mutableListOf<SettingsChange>()
        val shouldStopCounts = mutableMapOf<Pair<String, Boolean>, Int>()
        val autoPassCounts = mutableMapOf<String, Int>()
        val phaseCounts = mutableMapOf<String, Int>()
        var totalGSMs = 0
        var edictalCount = 0
        var lastTurnInfo: RecordingDecoder.TurnInfoSummary? = null

        for (msg in messages) {
            // Track latest turnInfo
            if (msg.turnInfo != null) lastTurnInfo = msg.turnInfo

            // Count GSMs
            if (msg.greType == "GameStateMessage") totalGSMs++

            // Count edictals
            if (msg.edictal != null) edictalCount++

            // Priority grants (AARs)
            if (msg.hasActionsAvailableReq && msg.actions.isNotEmpty()) {
                val ti = lastTurnInfo
                val stopActions = msg.actions.filter { it.shouldStop == true }.map { it.type }
                val noStopActions = msg.actions.filter { it.shouldStop != true }.map { it.type }
                val highlights = msg.actions.mapNotNull { it.highlight }
                val phase = ti?.phase ?: "?"
                val step = ti?.step ?: "?"
                val phaseKey = if (step != "None" && step != "?") "$phase/$step" else phase

                grants.add(
                    PriorityGrant(
                        gsId = msg.gsId,
                        turn = ti?.turn ?: 0,
                        phase = phase,
                        step = step,
                        activePlayer = ti?.activePlayer ?: 0,
                        priorityPlayer = ti?.priorityPlayer ?: 0,
                        actionTypes = msg.actions.map { it.type },
                        shouldStopActions = stopActions,
                        noStopActions = noStopActions,
                        highlights = highlights,
                    ),
                )
                phaseCounts[phaseKey] = (phaseCounts[phaseKey] ?: 0) + 1

                // shouldStop profile
                for (a in msg.actions) {
                    val key = a.type to (a.shouldStop == true)
                    shouldStopCounts[key] = (shouldStopCounts[key] ?: 0) + 1
                }
            }

            // Priority responses (PerformActionResp)
            if (msg.greType == "PerformActionResp" && msg.clientAction != null) {
                val ca = msg.clientAction
                val firstAction = ca.actions.firstOrNull()
                val ap = ca.autoPassPriority ?: "None"
                autoPassCounts[ap] = (autoPassCounts[ap] ?: 0) + 1

                responses.add(
                    PriorityResponse(
                        gsId = msg.gsId,
                        actionType = firstAction?.type ?: "?",
                        grpId = firstAction?.grpId ?: 0,
                        cardName = firstAction?.grpId?.takeIf { it != 0 }?.let { CardDb.getCardName(it) },
                        autoPassPriority = ca.autoPassPriority,
                    ),
                )
            }

            // Settings changes
            if (msg.greType == "SetSettingsReq" && msg.clientSettings != null) {
                val cs = msg.clientSettings
                settingsChanges.add(
                    SettingsChange(
                        index = msg.index,
                        autoPassOption = cs.autoPassOption,
                        stackAutoPassOption = cs.stackAutoPassOption,
                        smartStops = cs.smartStops,
                        stopCount = cs.stops.size,
                        stops = cs.stops,
                        transientStopCount = cs.transientStops.size,
                        yieldCount = cs.yields.size,
                        clearAllStops = cs.clearAllStops,
                        clearAllYields = cs.clearAllYields,
                    ),
                )
            }
        }

        val shouldStopProfile = shouldStopCounts.entries
            .groupBy { it.key.first }
            .map { (type, entries) ->
                val trueCount = entries.firstOrNull { it.key.second }?.value ?: 0
                val falseCount = entries.firstOrNull { !it.key.second }?.value ?: 0
                ShouldStopProfile(type, trueCount, falseCount)
            }
            .sortedByDescending { it.shouldStopTrue + it.shouldStopFalse }

        val totalAARs = grants.size
        return Analysis(
            totalGSMs = totalGSMs,
            totalAARs = totalAARs,
            grantRate = if (totalGSMs > 0) totalAARs.toDouble() / totalGSMs else 0.0,
            grants = grants,
            responses = responses,
            settingsChanges = settingsChanges,
            shouldStopProfile = shouldStopProfile,
            autoPassDistribution = autoPassCounts,
            edictalCount = edictalCount,
            phaseDistribution = phaseCounts.toSortedMap(),
        )
    }

    /** Human-readable priority timeline report. */
    fun formatReport(analysis: Analysis): String = buildString {
        appendLine("=== Priority Analysis ===")
        appendLine()
        appendLine("Overview:")
        appendLine(
            "  GSMs: ${analysis.totalGSMs}  AARs: ${analysis.totalAARs}  " +
                "Grant rate: ${"%.1f".format(analysis.grantRate * 100)}%  " +
                "Edictals: ${analysis.edictalCount}",
        )
        appendLine()

        appendLine("Phase distribution:")
        for ((phase, count) in analysis.phaseDistribution) {
            appendLine("  %-30s %d".format(phase, count))
        }
        appendLine()

        appendLine("shouldStop profile:")
        for (p in analysis.shouldStopProfile) {
            appendLine("  %-20s stop=%d  noStop=%d".format(p.actionType, p.shouldStopTrue, p.shouldStopFalse))
        }
        appendLine()

        appendLine("autoPassPriority distribution:")
        for ((key, count) in analysis.autoPassDistribution) {
            appendLine("  %-10s %d".format(key, count))
        }
        appendLine()

        if (analysis.settingsChanges.isNotEmpty()) {
            appendLine("Settings changes: ${analysis.settingsChanges.size}")
            for (sc in analysis.settingsChanges) {
                append("  [${sc.index}] autoPass=${sc.autoPassOption ?: "default"}")
                if (sc.stopCount > 0) append(" stops=${sc.stopCount}")
                if (sc.transientStopCount > 0) append(" transient=${sc.transientStopCount}")
                if (sc.yieldCount > 0) append(" yields=${sc.yieldCount}")
                if (sc.clearAllStops) append(" CLEAR_STOPS")
                if (sc.clearAllYields) append(" CLEAR_YIELDS")
                appendLine()
            }
            appendLine()
        }

        appendLine("Priority timeline:")
        for ((i, grant) in analysis.grants.withIndex()) {
            val resp = analysis.responses.getOrNull(i)
            val stopTypes = grant.shouldStopActions.groupingBy { it }.eachCount()
                .entries.joinToString(", ") { "${it.key}×${it.value}" }
            append("  T${grant.turn} %-12s".format("${grant.phase}/${grant.step}"))
            append(" [$stopTypes]")
            if (resp != null) {
                val card = resp.cardName?.let { " ($it)" } ?: ""
                append(" → ${resp.actionType}$card")
                if (resp.autoPassPriority != null) append(" autoPass=${resp.autoPassPriority}")
            }
            appendLine()
        }
    }
}
