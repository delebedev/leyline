package leyline.tooling.simclient

import leyline.bridge.types.SeatId
import leyline.copilot.ForgeAiPolicy
import leyline.copilot.SimDecision
import leyline.tooling.artifact.SyntheticArtifactSink
import leyline.tooling.headless.MatchFlowHarness
import leyline.tooling.simclient.GameStats
import leyline.tooling.simclient.SimClientFinding
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Drives one match end-to-end against a [MatchFlowHarness] using a greedy
 * policy, and emits scry-ts-parseable Player.log lines via [SyntheticArtifactSink].
 *
 * v0 policy:
 *   - mulligan: always keep (delegated to harness.connectAndKeep)
 *   - main phases: play one land per turn, then cast cheapest castable creature, else pass
 *   - declare attackers: send all
 *   - declare blockers: none
 *   - everything else: pass priority
 *
 * Loop runs until the engine reports game-over or [maxTurns] is reached.
 */
class SimClientDriver(
    val harness: MatchFlowHarness,
    private val log: SyntheticArtifactSink,
    private val maxTurns: Int = 50,
    private val maxIterations: Int = 2_000,
    private val turnStallThreshold: Int = TURN_STALL_THRESHOLD,
    private val connect: () -> Unit = { harness.connectAndKeep() },
    /**
     * When non-null, the driver consults this advisor for `ActionsAvailableReq`
     * decisions before falling back to greedy heuristics. Iteration 1 covers
     * priority-window action picking only; other prompt types stay greedy.
     */
    private val forgeAi: ForgeAiPolicy? = null,
    private val shadowAdvisor: Boolean = false,
    private val snapshotShadow: Boolean = false,
    private val snapshotConsult: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(SimClientDriver::class.java)
    private val snapshotProbe: SnapshotShadowProbe? = if (snapshotShadow) SnapshotShadowProbe(harness) else null
    private val snapshotDriver: SnapshotPromptDriver? = if (snapshotConsult) SnapshotPromptDriver(harness) else null

    /** Snapshot-shadow fidelity buckets per prompt family; null unless [snapshotShadow] is on. */
    internal fun snapshotShadowStats(): Map<String, SnapshotShadowProbe.Bucket>? = snapshotProbe?.stats()

    private var lastFlushedSize = 0
    private var stalledPrompt: String? = null
    private var stalledFingerprint: String? = null
    private var sawTerminalIntermission = false
    private val promptLedger = SimPromptLedger(harness)
    private val promptProgress = PromptProgressRecorder(harness)
    private val attemptLedger = ActionAttemptLedger { currentTurnOrNull() }
    private val submitter = SimDecisionSubmitter(harness)
    private val promptPolicy: SimPromptPolicy =
        forgeAi?.let { ForgeAiPromptPolicy(harness, it) } ?: GreedyPromptPolicy(harness)
    private var connectMs = 0L
    private var stepTotalMs = 0L
    private var stepMaxMs = 0L
    private var flushTotalMs = 0L
    private var flushMaxMs = 0L
    private var autoPassTotalMs = 0L
    private var autoPassMaxMs = 0L
    private val policyTotalMsByPrompt = mutableMapOf<String, Long>()
    private val policyMaxMsByPrompt = mutableMapOf<String, Long>()
    private val submitTotalMsByDecision = mutableMapOf<String, Long>()
    private val submitMaxMsByDecision = mutableMapOf<String, Long>()

    private companion object {
        const val TURN_STALL_THRESHOLD = 200
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun runOneGame(): GameStats {
        val tap = GameLogCollector().apply { start() }
        val t0 = System.nanoTime()
        val connectT0 = System.nanoTime()
        connect()
        connectMs = elapsedMsSince(connectT0)
        flushNewMessagesToLog()

        var iter = 0
        var hitIterCap = false
        var completionReason = "natural"
        var cleanupConcede = false
        var stuckAtPriority = 0
        var lastTurn = currentTurnOrNull() ?: 0
        var lastTurnIter = 0
        while (true) {
            if (harness.isGameOver()) break
            if (sawTerminalIntermission) break
            val currentTurn = currentTurnOrNull() ?: break // bridge torn down
            if (currentTurn >= maxTurns) {
                completionReason = "max-turns"
                break
            }
            if (iter >= maxIterations) {
                hitIterCap = true
                completionReason = "iter-cap"
                break
            }
            iter++
            val before = harness.allMessages.size
            val stepT0 = System.nanoTime()
            val acted = takeOneStep()
            recordStep(elapsedMsSince(stepT0))
            flushNewMessagesToLog()
            // Only count "no-progress" iterations when we actually tried to
            // submit something. Race-guard skips (no pending action) leave the
            // engine async — give it room before declaring a stall.
            if (acted && harness.allMessages.size == before) {
                attemptLedger.markNoProgress()
                stuckAtPriority++
                if (stuckAtPriority >= 3) {
                    recordStallPrompt()
                    completionReason = "no-progress"
                    logger.warn("SimClientDriver: no progress after iter $iter, breaking")
                    break
                }
            } else if (acted) {
                attemptLedger.markProgress()
                stuckAtPriority = 0
            }
            // Detect "turn-stall" — too many iterations on the same turn means
            // engine is grinding combat-phase priority windows or AI is stuck
            // proposing unaffordable casts. Concede to end the game cleanly.
            val cur = currentTurnOrNull() ?: break
            if (cur != lastTurn) {
                lastTurn = cur
                lastTurnIter = iter
            } else if (iter - lastTurnIter > turnStallThreshold) {
                recordStallPrompt()
                completionReason = "turn-stall"
                logger.warn("SimClientDriver: turn $cur stalled for >$turnStallThreshold iters, conceding")
                concedeAndFlush(reason = "stall", logFailureAtError = true)
                break
            }
        }
        // Final cleanup — if loop exited but game is still active (max-turns,
        // no-progress break, or iter cap), concede so the game produces a proper
        // game-over sequence and gameOver=true in stats.
        if (!harness.isGameOver() && !sawTerminalIntermission) {
            cleanupConcede = true
            if (completionReason == "natural") completionReason = "cleanup"
            concedeAndFlush(reason = "cleanup", logFailureAtError = false)
        } else if (sawTerminalIntermission) {
            completionReason = "terminal-intermission"
        }
        log.flush()
        val histogram =
            harness.allMessages
                .filter { isSimPrompt(it) }
                .groupingBy { it.type }
                .eachCount()
        val promptRouteAudit = PromptRouteAuditor.audit(promptHistory(), histogram)
        val durationMs = (System.nanoTime() - t0) / 1_000_000
        val logs = tap.stopAndDrain()
        val policyTelemetry =
            snapshotDriver?.telemetry()
                ?: (promptPolicy as? ForgeAiPromptPolicy)?.telemetry()
                ?: SimPromptPolicyTelemetry.Empty
        val simFindings = detectReplayLoopFindings(policyTelemetry.targetChoices, policyTelemetry.targetChoiceSamples)
        val promptStats = promptLedger.stats()
        val attemptStats = attemptLedger.stats()
        val outcome = finalOutcome()
        snapshotProbe?.logSummary("turn=${currentTurnOrNull() ?: lastTurn},reason=$completionReason")
        val semanticAgreement =
            snapshotProbe
                ?.stats()
                ?.flatMap { (prompt, bucket) ->
                    listOf(
                        "$prompt.match" to bucket.match,
                        "$prompt.mismatch" to bucket.mismatch,
                        "$prompt.uncovered" to bucket.uncovered,
                        "$prompt.bothUnavailable" to bucket.bothUnavailable,
                        "$prompt.error" to bucket.error,
                    )
                }?.toMap()
                .orEmpty()
        return GameStats(
            turn = currentTurnOrNull() ?: lastTurn,
            gameOver = harness.isGameOver() || sawTerminalIntermission,
            winnerSeat = outcome.winnerSeat,
            loserSeat = outcome.loserSeat,
            finalLifeBySeat = outcome.lifeBySeat,
            finalStatusBySeat = outcome.statusBySeat,
            iterations = iter,
            totalMessages = harness.allMessages.size,
            promptHistogram = histogram,
            hitIterCap = hitIterCap,
            durationMs = durationMs,
            aiConsulted = policyTelemetry.consultedTotal,
            aiChose = policyTelemetry.choseTotal,
            aiConsultedByPrompt = policyTelemetry.consulted,
            aiChoseByPrompt = policyTelemetry.chose,
            aiTotalMs = policyTelemetry.totalAiMs,
            aiTotalMsByPrompt = policyTelemetry.totalMs,
            aiMaxMsByPrompt = policyTelemetry.maxMs,
            targetChoiceCounts = policyTelemetry.targetChoices,
            targetChoiceSamples = policyTelemetry.targetChoiceSamples,
            advisorUnavailableByReason =
                mergeCounts(
                    policyTelemetry.advisorUnavailableByReason,
                    snapshotProbe?.unavailableReasons().orEmpty(),
                ),
            snapshotFidelityGrades = snapshotDriver?.fidelityGrades() ?: snapshotProbe?.fidelityGrades().orEmpty(),
            snapshotImportFindings = snapshotDriver?.importFindings() ?: snapshotProbe?.importFindings().orEmpty(),
            snapshotDecisionSources = snapshotDriver?.decisionSources() ?: snapshotProbe?.decisionSources().orEmpty(),
            snapshotSemanticAgreement = semanticAgreement,
            snapshotSemanticMismatchSamples = snapshotProbe?.mismatchSamples().orEmpty(),
            warnsByLogger = logs.warnsByLogger,
            errorsByType = logs.errorsByType,
            logErrorSamples = logs.errorSamples,
            validationViolationsByCheck =
                harness.validatingSink
                    ?.violationsByCheck
                    ?.toMap()
                    .orEmpty(),
            validationViolations =
                harness.validatingSink
                    ?.violations
                    ?.take(10)
                    .orEmpty(),
            stalledPrompt = stalledPrompt,
            stalledFingerprint = stalledFingerprint,
            completionReason = completionReason,
            cleanupConcede = cleanupConcede,
            promptRetiredByReason = promptStats.retiredByReason,
            decisionOutcomes = attemptStats.outcomes,
            actionAttemptsByType = attemptStats.submittedByType,
            noPendingByDecision = attemptStats.noPendingByDecision,
            skippedAlreadyTried = attemptStats.skippedAlreadyTried,
            connectMs = connectMs,
            stepTotalMs = stepTotalMs,
            stepMaxMs = stepMaxMs,
            flushTotalMs = flushTotalMs,
            flushMaxMs = flushMaxMs,
            autoPassTotalMs = autoPassTotalMs,
            autoPassMaxMs = autoPassMaxMs,
            policyTotalMsByPrompt = policyTotalMsByPrompt.toMap(),
            policyMaxMsByPrompt = policyMaxMsByPrompt.toMap(),
            submitTotalMsByDecision = submitTotalMsByDecision.toMap(),
            submitMaxMsByDecision = submitMaxMsByDecision.toMap(),
            promptRequestsByKind = promptRouteAudit.requestsByKind,
            promptRequestSamplesByKind = promptRouteAudit.samplesByKind,
            promptRouteFindings = promptRouteAudit.findings,
            simFindings = simFindings,
            promptProgressSamples = promptProgress.snapshot(),
        )
    }

    private fun mergeCounts(
        first: Map<String, Int>,
        second: Map<String, Int>,
    ): Map<String, Int> =
        buildMap {
            first.forEach { (key, count) -> put(key, count) }
            second.forEach { (key, count) -> put(key, getOrDefault(key, 0) + count) }
        }

    private fun promptHistory(): List<leyline.bridge.handoff.PromptRecord> =
        runCatching { harness.bridge.promptBridge(SeatId(1)).history }.getOrDefault(emptyList())

    private data class FinalOutcome(
        val winnerSeat: Int?,
        val loserSeat: Int?,
        val lifeBySeat: Map<String, Int>,
        val statusBySeat: Map<String, String>,
    )

    private fun finalOutcome(): FinalOutcome {
        val lifeBySeat = mutableMapOf<Int, Int>()
        val statusBySeat = mutableMapOf<Int, String>()
        for (msg in harness.allMessages) {
            if (!msg.hasGameStateMessage()) continue
            for (player in msg.gameStateMessage.playersList) {
                val seat = player.systemSeatNumber
                if (seat == 0) continue
                lifeBySeat[seat] = player.lifeTotal
                statusBySeat[seat] = player.status.name
            }
        }
        val loser = statusBySeat.entries.firstOrNull { (_, status) -> status.contains("Loss", ignoreCase = true) }?.key
        val winner =
            loser?.let { lostSeat ->
                statusBySeat.keys.firstOrNull { it != lostSeat && !statusBySeat.getValue(it).contains("Loss", ignoreCase = true) }
            } ?: inferWinnerFromLife(lifeBySeat)
        return FinalOutcome(
            winnerSeat = winner,
            loserSeat = loser ?: winner?.let { wonSeat -> lifeBySeat.keys.firstOrNull { it != wonSeat } },
            lifeBySeat = lifeBySeat.mapKeys { it.key.toString() },
            statusBySeat = statusBySeat.mapKeys { it.key.toString() },
        )
    }

    private fun inferWinnerFromLife(lifeBySeat: Map<Int, Int>): Int? {
        val seat1 = lifeBySeat[1]
        val seat2 = lifeBySeat[2]
        return when {
            seat1 != null && seat2 != null && seat1 > 0 && seat2 <= 0 -> 1
            seat1 != null && seat2 != null && seat2 > 0 && seat1 <= 0 -> 2
            else -> null
        }
    }

    private fun concedeAndFlush(
        reason: String,
        logFailureAtError: Boolean,
    ) {
        try {
            harness.concede()
            flushNewMessagesToLog()
        } catch (t: Throwable) {
            val msg = "SimClientDriver: $reason concede failed: ${t::class.simpleName}: ${t.message}"
            if (logFailureAtError) logger.error(msg, t) else logger.warn(msg)
        }
    }

    /**
     * Returns true if a real action / response was submitted; false if we
     * skipped due to no pending action (engine is async and not yet ready —
     * the caller should NOT count this toward the no-progress safety).
     */
    private fun takeOneStep(): Boolean {
        val prompt = promptLedger.activePrompt()
        if (prompt != null && !prompt.requiresActionBridge) {
            return respondToPrompt(prompt)
        }

        // Race guard: if no action is currently pending, the engine has already
        // published the next horizon for the last observed prompt. Submitting
        // now can target a retired action. Observe the committed output instead
        // and pick up the next real prompt on the next iteration.
        if (!harness.hasPendingAction()) {
            if (prompt != null) {
                promptLedger.retire(prompt, "no-pending")
                attemptLedger.markNoPending("pre-submit:${prompt.type.name}")
            }
            return awaitClientOutputAndDrain()
        }
        val active =
            prompt ?: run {
                if (harness.isAiTurn()) return awaitClientOutputAndDrain()
                harness.passPriority()
                return true
            }
        return respondToPrompt(active)
    }

    private fun awaitClientOutputAndDrain(): Boolean {
        val before = harness.allMessages.size
        harness.awaitNextClientOutput()
        return harness.allMessages.size > before
    }

    private fun respondToPrompt(prompt: ActivePrompt): Boolean {
        snapshotProbe?.observe(prompt.msg)
        val beforeMessages = harness.allMessages.size
        val beforeLast = harness.allMessages.lastOrNull()
        val sourceBefore = promptProgress.sourceSnapshot(prompt)
        val policyT0 = System.nanoTime()
        val snapshotResponse = snapshotDriver?.respond(prompt.msg)
        var response = snapshotResponse ?: promptPolicy.respondToPrompt(prompt, attemptLedger)
        var decisionPrefix =
            when {
                snapshotDriver == null -> ""
                snapshotResponse == null -> "snapshot-fallback:"
                else -> "snapshot:"
            }
        recordMapTiming(
            totals = policyTotalMsByPrompt,
            maxes = policyMaxMsByPrompt,
            key = prompt.type.name,
            elapsedMs = elapsedMsSince(policyT0),
        )
        val submitT0 = System.nanoTime()
        var submitResult = submitter.submit(response.value)
        if (snapshotResponse != null && submitResult == SimSubmitResult.NotSubmitted) {
            response = promptPolicy.respondToPrompt(prompt, attemptLedger)
            decisionPrefix = "snapshot-fallback:"
            submitResult = submitter.submit(response.value)
        }
        val decision = (response.value as? SimPromptResponseValue.Decision)?.decision
        val submittedAction = (decision as? SimDecision.PerformAction)?.action
        val decisionKind = "$decisionPrefix${response.value.kind}"
        promptProgress.record(
            prompt = prompt,
            decisionKind = decisionKind,
            targetIds = decision?.targetIds().orEmpty(),
            submitResult = submitResult,
            beforeMessages = beforeMessages,
            beforeLast = beforeLast,
            sourceBefore = sourceBefore,
        )
        recordMapTiming(
            totals = submitTotalMsByDecision,
            maxes = submitMaxMsByDecision,
            key = decisionKind,
            elapsedMs = elapsedMsSince(submitT0),
        )
        when (submitResult) {
            SimSubmitResult.Submitted -> {
                if (snapshotDriver != null) {
                    val retryFingerprints =
                        submittedAction
                            ?.takeIf { decisionPrefix == "snapshot:" || response.aarActionFingerprint != null }
                            ?.retryFingerprints()
                            .orEmpty()
                    attemptLedger.markSubmitted(retryFingerprints, decisionKind)
                } else if (response.aarActionFingerprint != null && submittedAction != null) {
                    attemptLedger.markSubmitted(submittedAction.retryFingerprints(), decisionKind)
                }
            }
            SimSubmitResult.NoPending -> {
                promptLedger.retire(prompt, if (snapshotDriver == null) "no-pending-submit" else "no-pending-snapshot")
                attemptLedger.markNoPending(decisionKind)
            }
            SimSubmitResult.NotSubmitted -> {
                when {
                    response.value == SimPromptResponseValue.RetirePrompt -> promptLedger.retire(prompt, "policy-retired")
                    snapshotDriver != null -> promptLedger.retire(prompt, "snapshot-unavailable")
                }
            }
        }
        response.markAllHandledOfType?.let { promptLedger.markAllHandled(it, throughMsgId = prompt.msgId) }
        if (response.markHandled && submitResult != SimSubmitResult.NoPending) promptLedger.markHandled(prompt)
        // A responder can answer more than the prompt it was handed: the targeting
        // pair echoes an iterative re-prompt that the same call submits against.
        // Without this the echo looks outstanding, and answering it later reaches a
        // window that closed when the pair completed.
        harness.takeConsumedPromptMsgIds().forEach { promptLedger.markHandled(it) }
        if (response.value == SimPromptResponseValue.Terminal) sawTerminalIntermission = true
        return submitResult == SimSubmitResult.Submitted
    }

    /** Returns current turn number, or null if the game/bridge has been torn down. */
    private fun currentTurnOrNull(): Int? = runCatching { harness.turn() }.getOrNull()

    private fun recordStallPrompt() {
        val stall = promptLedger.stallPrompt()
        stalledPrompt = stall.prompt
        stalledFingerprint = stall.fingerprint
    }

    private fun flushNewMessagesToLog() {
        val total = harness.allMessages.size
        if (total <= lastFlushedSize) return
        val flushT0 = System.nanoTime()
        val fresh = harness.allMessages.subList(lastFlushedSize, total).toList()
        log.writeBundle(fresh)
        lastFlushedSize = total
        recordFlush(elapsedMsSince(flushT0))
    }

    private fun elapsedMsSince(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private fun recordStep(elapsedMs: Long) {
        stepTotalMs += elapsedMs
        stepMaxMs = maxOf(stepMaxMs, elapsedMs)
    }

    private fun recordFlush(elapsedMs: Long) {
        flushTotalMs += elapsedMs
        flushMaxMs = maxOf(flushMaxMs, elapsedMs)
    }

    private fun recordAutoPass(elapsedMs: Long) {
        autoPassTotalMs += elapsedMs
        autoPassMaxMs = maxOf(autoPassMaxMs, elapsedMs)
    }

    private fun recordMapTiming(
        totals: MutableMap<String, Long>,
        maxes: MutableMap<String, Long>,
        key: String,
        elapsedMs: Long,
    ) {
        totals.merge(key, elapsedMs) { a, b -> a + b }
        maxes.merge(key, elapsedMs) { a, b -> maxOf(a, b) }
    }
}

internal fun chooseSimClientCastingTimeOptionId(
    msg: GREToClientMessage?,
    acceptOptionalCosts: Boolean,
): Int {
    if (!acceptOptionalCosts) return 0
    return msg
        ?.castingTimeOptionsReq
        ?.castingTimeOptionReqList
        ?.firstOrNull { it.ctoId != 0 }
        ?.ctoId
        ?: 0
}

internal fun detectReplayLoopFindings(
    targetChoiceCounts: Map<String, Int>,
    targetChoiceSamples: Map<String, String>,
    threshold: Int = REPLAY_LOOP_TARGET_THRESHOLD,
): List<SimClientFinding> =
    targetChoiceCounts.entries
        .filter { (_, count) -> count >= threshold }
        .map { (key, count) ->
            SimClientFinding(
                kind = "replay-loop-suspect",
                key = key,
                count = count,
                sample = targetChoiceSamples[key].orEmpty(),
            )
        }.sortedWith(compareByDescending<SimClientFinding> { it.count }.thenBy { it.key })

private const val REPLAY_LOOP_TARGET_THRESHOLD = 25
