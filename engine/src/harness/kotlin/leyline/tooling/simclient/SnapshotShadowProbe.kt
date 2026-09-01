package leyline.tooling.simclient

import leyline.bridge.types.SeatId
import leyline.copilot.CopilotProposalService
import leyline.copilot.PromptDecisionResult
import leyline.copilot.SimDecision
import leyline.copilot.SnapshotDecisionConsult
import leyline.tooling.headless.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Snapshot-fidelity probe. At each prompt the seat responds to, ask the SAME
 * decision brain ([CopilotProposalService]) twice — once on the active game,
 * once on a fresh game hydrated from its serialized state — and compare the
 * complete desired decisions.
 *
 * It never submits and builds the snapshot without frame events. Native response
 * encoding belongs to Copilot and is deliberately outside this semantic probe.
 *
 * Buckets per prompt family:
 *   - match        — desired decisions are equal
 *   - mismatch     — both chose, but their desired decisions differ
 *   - uncovered    — live chose, snapshot did not (hydration lost the
 *                    ability to answer at all)
 *   - bothUnavailable — neither side produced a desired decision
 *   - error        — state rebuild or consult threw
 */
internal class SnapshotShadowProbe(
    private val harness: MatchFlowHarness,
    private val seat: Int = 1,
) {
    private val log = LoggerFactory.getLogger(SnapshotShadowProbe::class.java)
    private val liveService by lazy { CopilotProposalService(harness.bridge, SeatId(seat)) }
    private val snapshotSource = SnapshotProposalSource(harness, seat)
    private val seen = mutableSetOf<Long>()

    data class Bucket(
        var probed: Int = 0,
        var match: Int = 0,
        var mismatch: Int = 0,
        var uncovered: Int = 0,
        var bothUnavailable: Int = 0,
        var error: Int = 0,
    )

    private val byPrompt = linkedMapOf<String, Bucket>()
    private val mismatchSamples = linkedMapOf<String, String>()
    private val selectTargetsClasses = linkedMapOf<String, Int>()
    private val selectTargetsClassSamples = linkedMapOf<String, String>()
    private val fidelityGrades = linkedMapOf<String, Int>()
    private val importFindings = linkedMapOf<String, Int>()
    private val decisionSources = linkedMapOf<String, Int>()
    private val unavailableReasons = linkedMapOf<String, Int>()

    /** Per-prompt-family counts accumulated so far. */
    fun stats(): Map<String, Bucket> = byPrompt.mapValues { it.value.copy() }

    /** First mismatch digest per prompt family. */
    fun mismatchSamples(): Map<String, String> = mismatchSamples.toMap()

    fun fidelityGrades(): Map<String, Int> = fidelityGrades.toMap()

    fun importFindings(): Map<String, Int> = importFindings.toMap()

    fun decisionSources(): Map<String, Int> = decisionSources.toMap()

    fun unavailableReasons(): Map<String, Int> = unavailableReasons.toMap()

    /** Probe [prompt] once. Deduped by (msgId, gsId) so loop revisits don't double-count. */
    fun observe(prompt: GREToClientMessage) {
        val fingerprint = (prompt.msgId.toLong() shl 32) or (prompt.gameStateId.toLong() and 0xffffffffL)
        if (!seen.add(fingerprint)) return

        val key = prompt.type.name
        val b = byPrompt.getOrPut(key) { Bucket() }
        b.probed++

        val live =
            runCatching { liveService.decide(prompt) }.getOrElse {
                b.error++
                log.debug("shadow live propose failed {}: {}", key, it.message)
                return
            }
        val snapshot =
            runCatching { snapshotSource.decide(prompt) }.getOrElse {
                b.error++
                unavailableReasons.merge("ConsultFailed", 1, Int::plus)
                log.debug("shadow snapshot consult failed {}: {}", key, it.message)
                return
            }
        recordTelemetry(snapshot)

        val liveDecision = (live as? PromptDecisionResult.Chosen)?.decision
        val snapshotDecision = (snapshot.result as? PromptDecisionResult.Chosen)?.decision
        when {
            liveDecision == null && snapshotDecision == null -> b.bothUnavailable++
            snapshotDecision == null -> {
                b.uncovered++
                sample(key, prompt, liveDecision, snapshotDecision, "snapshot-uncovered", snapshot.fidelity.grade)
            }
            liveDecision == null -> {
                b.mismatch++
                sample(key, prompt, liveDecision, snapshotDecision, "live-uncovered", snapshot.fidelity.grade)
            }
            liveDecision == snapshotDecision -> b.match++
            else -> {
                b.mismatch++
                sample(key, prompt, liveDecision, snapshotDecision, "decision-differ", snapshot.fidelity.grade)
            }
        }
        if (prompt.type == GREMessageType.SelectTargetsReq_695e && liveDecision != snapshotDecision) {
            classifySelectTargets(prompt, liveDecision, snapshotDecision)
        }
    }

    private fun recordTelemetry(snapshot: SnapshotDecisionConsult) {
        fidelityGrades.merge(snapshot.fidelity.grade, 1, Int::plus)
        snapshot.fidelity.unavailableReasons.forEach { importFindings.merge(it, 1, Int::plus) }
        when (val result = snapshot.result) {
            is PromptDecisionResult.Chosen -> decisionSources.merge(result.source.name, 1, Int::plus)
            is PromptDecisionResult.Unavailable -> unavailableReasons.merge(result.reason.name, 1, Int::plus)
        }
    }

    /**
     * Break a SelectTargets divergence into its sub-cause so the aggregate
     * match rate is not read as one problem when it is several. Buckets:
     *  - same-ids — one side did not produce a target decision, but neither
     *    side selected a target id.
     *  - count-diff — different number of targets (optional decline vs pick,
     *    or multi-target count mismatch).
     *  - cross-side — live and snapshot target opposite sides (own vs enemy):
     *    the fallback's "enemy-first" pick is on the wrong side of the spell.
     *  - same-side-diff-pick — same side, different object: fallback-vs-Forge's
     *    considered pick (a quality gap, still legal).
     *  - player-vs-object — one picks a player/face, the other a permanent.
     */
    private fun classifySelectTargets(
        prompt: GREToClientMessage,
        live: SimDecision?,
        snapshot: SimDecision?,
    ) {
        val liveIds = (live as? SimDecision.SelectTargets)?.targetInstanceIds.orEmpty()
        val snapIds = (snapshot as? SimDecision.SelectTargets)?.targetInstanceIds.orEmpty()
        val cls =
            when {
                liveIds == snapIds -> "same-ids"
                liveIds.size != snapIds.size -> "count-diff(${liveIds.size}->${snapIds.size})"
                else -> {
                    val liveSides = liveIds.map { sideOf(it) }
                    val snapSides = snapIds.map { sideOf(it) }
                    when {
                        liveSides.toSet() != snapSides.toSet() -> "cross-side($liveSides->$snapSides)"
                        else -> "same-side-diff-pick(${liveSides.firstOrNull()})"
                    }
                }
            }
        selectTargetsClasses.merge(cls, 1) { a, b -> a + b }
        selectTargetsClassSamples.putIfAbsent(
            cls,
            "src=${nameOf(prompt.selectTargetsReq.sourceId)} " +
                "live=${liveIds.map { "$it:${nameOf(it)}:${sideOf(it)}" }} " +
                "snap=${snapIds.map { "$it:${nameOf(it)}:${sideOf(it)}" }}",
        )
    }

    /** own / enemy / player, relative to the probed seat. */
    private fun sideOf(instanceId: Int): String {
        val obj = harness.accumulator.objects[instanceId] ?: return "player"
        return if (obj.controllerSeatId == seat) "own" else "enemy"
    }

    private fun nameOf(instanceId: Int): String {
        val obj = harness.accumulator.objects[instanceId] ?: return "player/face"
        return runCatching { harness.bridge.cardRepository.findNameByGrpId(obj.grpId) }.getOrNull() ?: "grp:${obj.grpId}"
    }

    private fun sample(
        key: String,
        prompt: GREToClientMessage,
        live: SimDecision?,
        snapshot: SimDecision?,
        reason: String,
        fidelityGrade: String,
    ) {
        val sampleKey = "$key.$reason"
        if (mismatchSamples.containsKey(sampleKey)) return
        val turn = runCatching { harness.turn() }.getOrNull()
        val digest =
            "reason=$reason turn=$turn gsId=${prompt.gameStateId} fidelity=$fidelityGrade " +
                "live=${live?.auditDigest() ?: "unavailable"} snapshot=${snapshot?.auditDigest() ?: "unavailable"}"
        mismatchSamples[sampleKey] = digest
        log.warn("SNAPSHOT-SHADOW mismatch {} :: {}", sampleKey, digest)
    }

    fun logSummary(tag: String) {
        val probed = byPrompt.values.sumOf { it.probed }
        if (probed == 0) return
        val match = byPrompt.values.sumOf { it.match }
        val mismatch = byPrompt.values.sumOf { it.mismatch }
        val uncovered = byPrompt.values.sumOf { it.uncovered }
        val covered = match + mismatch
        val rate = if (covered > 0) 100.0 * match / covered else 0.0
        val perPrompt =
            byPrompt.entries.joinToString(";") { (k, v) ->
                "$k[p=${v.probed},m=${v.match},x=${v.mismatch},u=${v.uncovered},b=${v.bothUnavailable},e=${v.error}]"
            }
        log.info(
            "SNAPSHOT-SHADOW summary tag={} probed={} match={} mismatch={} uncovered={} decisionMatch%={} :: {}",
            tag,
            probed,
            match,
            mismatch,
            uncovered,
            "%.1f".format(rate),
            perPrompt,
        )
        if (selectTargetsClasses.isNotEmpty()) {
            log.info(
                "SNAPSHOT-SHADOW selectTargets-classes tag={} :: {} :: samples={}",
                tag,
                selectTargetsClasses.entries.joinToString(";") { (k, v) -> "$k=$v" },
                selectTargetsClassSamples,
            )
        }
    }

    /** SelectTargets divergence sub-cause histogram, for classifying the gap. */
    fun selectTargetsClasses(): Map<String, Int> = selectTargetsClasses.toMap()
}
