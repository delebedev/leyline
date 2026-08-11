package leyline.tooling.simclient

import leyline.bridge.types.SeatId
import leyline.copilot.CopilotProposal
import leyline.copilot.CopilotProposalService
import leyline.copilot.SnapshotConsult
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.tooling.headless.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Snapshot-fidelity probe. At each prompt the seat responds to, ask the SAME
 * decision brain ([CopilotProposalService]) twice — once on the authoritative
 * live game, once on a fresh game hydrated from that game's serialized wire
 * state — and compare the two response byte strings.
 *
 * Both consults are stamped from the same prompt (identical gsId + respId), so a
 * byte difference is a hydration-fidelity gap, not an envelope difference, and
 * hydration is the only variable between the two calls. Purely observational:
 * never submits, and passes an empty event log to the snapshot build so it never
 * drains the live bridge's open frame.
 *
 * Buckets per prompt family:
 *   - match        — response bytes identical (hydration preserved the decision)
 *   - mismatch     — both produced bytes, but they differ (a hydration gap)
 *   - uncovered    — live produced bytes, snapshot did not (hydration lost the
 *                    ability to answer at all)
 *   - bothUncovered — neither side has a response builder (a decoder gap, not a
 *                    hydration gap)
 *   - error        — capture / hydrate / consult threw
 */
internal class SnapshotShadowProbe(
    private val harness: MatchFlowHarness,
    private val seat: Int = 1,
) {
    private val log = LoggerFactory.getLogger(SnapshotShadowProbe::class.java)
    private val liveService by lazy { CopilotProposalService(harness.bridge, SeatId(seat)) }
    private val seen = mutableSetOf<Long>()

    data class Bucket(
        var probed: Int = 0,
        var match: Int = 0,
        var mismatch: Int = 0,
        var uncovered: Int = 0,
        var bothUncovered: Int = 0,
        var error: Int = 0,
    )

    private val byPrompt = linkedMapOf<String, Bucket>()
    private val mismatchSamples = linkedMapOf<String, String>()
    private val selectTargetsClasses = linkedMapOf<String, Int>()
    private val selectTargetsClassSamples = linkedMapOf<String, String>()

    /** Per-prompt-family counts accumulated so far. */
    fun stats(): Map<String, Bucket> = byPrompt.mapValues { it.value.copy() }

    /** First mismatch digest per prompt family. */
    fun mismatchSamples(): Map<String, String> = mismatchSamples.toMap()

    /** Probe [prompt] once. Deduped by (msgId, gsId) so loop revisits don't double-count. */
    fun observe(prompt: GREToClientMessage) {
        val fingerprint = (prompt.msgId.toLong() shl 32) or (prompt.gameStateId.toLong() and 0xffffffffL)
        if (!seen.add(fingerprint)) return

        val key = prompt.type.name
        val b = byPrompt.getOrPut(key) { Bucket() }
        b.probed++

        val live =
            runCatching { liveService.propose(prompt) }.getOrElse {
                b.error++
                log.debug("shadow live propose failed {}: {}", key, it.message)
                return
            }
        val snapshot =
            runCatching { snapshotProposal(prompt) }.getOrElse {
                b.error++
                log.debug("shadow snapshot consult failed {}: {}", key, it.message)
                return
            }

        val liveMessages = live.responses
        val snapshotMessages = snapshot.responses
        when {
            liveMessages.isEmpty() && snapshotMessages.isEmpty() -> b.bothUncovered++
            snapshotMessages.isEmpty() -> {
                b.uncovered++
                sample(key, prompt, live, snapshot, "snapshot-uncovered")
            }
            liveMessages.isEmpty() -> {
                b.mismatch++
                sample(key, prompt, live, snapshot, "live-uncovered")
            }
            liveMessages == snapshotMessages -> b.match++
            else -> {
                b.mismatch++
                sample(key, prompt, live, snapshot, "bytes-differ")
            }
        }
        if (prompt.type == GREMessageType.SelectTargetsReq_695e && (liveMessages != snapshotMessages)) {
            classifySelectTargets(prompt, live, snapshot)
        }
    }

    /**
     * Break a SelectTargets divergence into its sub-cause so the aggregate
     * match rate is not read as one problem when it is several. Buckets:
     *  - encoding/same-ids — same target ids, different bytes (targetIdx / group
     *    encoding), a response-builder issue, not a decision one.
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
        live: CopilotProposal,
        snapshot: CopilotProposal,
    ) {
        val liveIds = live.responseIds
        val snapIds = snapshot.responseIds
        val cls =
            when {
                live.responses.isNotEmpty() && snapshot.responses.isNotEmpty() && liveIds == snapIds -> "encoding/same-ids"
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

    /** Non-invasive: full-state capture + empty event log, hydrate a throwaway game, consult. */
    private fun snapshotProposal(prompt: GREToClientMessage): CopilotProposal {
        val game = harness.bridge.getGame() ?: error("no live game")
        val snap = GsmSnapshot.capture(game, harness.bridge, "shadow", 0)
        val gsm =
            StateMapper
                .buildFromSnapshot(
                    snap = snap,
                    gameStateId = 0,
                    matchId = "shadow",
                    bridge = harness.bridge,
                    viewingSeatId = seat,
                    events = FrameEventLog(emptyList()),
                ).gsm
        return SnapshotConsult.consult(gsm, prompt, seat, harness.bridge.cardRepository).proposal
    }

    private fun sample(
        key: String,
        prompt: GREToClientMessage,
        live: CopilotProposal,
        snapshot: CopilotProposal,
        reason: String,
    ) {
        if (mismatchSamples.containsKey(key)) return
        val turn = runCatching { harness.turn() }.getOrNull()
        val diffAt = firstDiffOffset(live.responses, snapshot.responses)
        val digest =
            "reason=$reason turn=$turn gsId=${prompt.gameStateId} diffAtChar=$diffAt " +
                "live={intent=${live.intent},ids=${live.responseIds},responses=${live.responses}} " +
                "snap={intent=${snapshot.intent},ids=${snapshot.responseIds},responses=${snapshot.responses}}"
        mismatchSamples[key] = digest
        log.warn("SNAPSHOT-SHADOW mismatch {} :: {}", key, digest)
    }

    /** Character index of the first difference between encoded deliveries, or -1 when either is empty. */
    private fun firstDiffOffset(
        a: List<String>,
        b: List<String>,
    ): Int {
        if (a.isEmpty() || b.isEmpty()) return -1
        val left = a.joinToString(",")
        val right = b.joinToString(",")
        val n = minOf(left.length, right.length)
        for (i in 0 until n) if (left[i] != right[i]) return i
        return if (left.length != right.length) n else -1
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
                "$k[p=${v.probed},m=${v.match},x=${v.mismatch},u=${v.uncovered},b=${v.bothUncovered},e=${v.error}]"
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
