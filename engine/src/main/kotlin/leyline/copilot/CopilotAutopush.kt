package leyline.copilot

import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Event-driven push for a copilot-driven seat: when the live game prompts the
 * seat, compute the response in-process (no snapshot hydration — the game is
 * right here) and push it to the injection bridge, so the client submits it.
 *
 * This is the leyline-side half of the poll→push flip. The host emits a prompt
 * at the send seam and simultaneously computes + pushes the answer; the host is
 * then blocked awaiting that seat's action, so the game state stays fixed while
 * the response is computed off the session thread. Two-round-trip prompts
 * (combat/targeting) fall out naturally: the host re-prompts after the client's
 * first response, which re-fires the seam and pushes the next step.
 *
 * **Self-healing.** A single injected response can be silently dropped by the
 * native inject bridge. Fire-and-forget then wedges:
 * the host waits forever for a response that never arrives, no new prompt fires,
 * and the seam never re-triggers. So each push re-injects on a bounded loop and
 * confirms the response actually landed by watching
 * [leyline.game.bundle.MessageCounter.responsesAccepted] advance — the host only
 * bumps it when it accepts a valid response for the pending prompt. The happy
 * path (first inject lands) exits within one poll interval; a drop costs a few
 * spaced re-injects instead of a dead game.
 */
class CopilotAutopush(
    private val gameBridge: GameBridge,
    private val seatId: SeatId,
    private val bridgeUrl: String,
) {
    private val log = LoggerFactory.getLogger(CopilotAutopush::class.java)
    private val service = CopilotProposalService(gameBridge, seatId)

    // Single thread: pushes are serialized, off the session thread.
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "copilot-autopush").apply { isDaemon = true } }

    private companion object {
        const val MAX_INJECT_ATTEMPTS = 6
        const val LAND_POLL_MS = 80L
        const val LAND_POLL_CHECKS = 8 // ~640ms per attempt to observe a landed response
    }

    /** Compute + push a response for [prompt] on the push thread, re-injecting until it lands. */
    fun onPrompt(prompt: GREToClientMessage) {
        exec.submit {
            runCatching {
                // Coalesce prompt bursts. The exec is single-threaded, so during a
                // rapid burst (combat with many creatures) prompts queue behind this
                // task while the engine keeps emitting. A prompt that is already
                // superseded when we dequeue it can never land — its respId is stale,
                // the guard rejects it, and burning MAX_INJECT_ATTEMPTS on it just
                // lets the queue back up further. Skip straight to the latest so the
                // queue drains to the one prompt the engine is actually awaiting.
                if (gameBridge.messageCounter.lastPromptMsgId() > prompt.msgId) {
                    log.debug("autopush: {} msgId={} superseded at dequeue, skipping", prompt.type, prompt.msgId)
                    return@submit
                }
                val proposal = service.propose(prompt)

                // Server-side pass. An empty priority window answered with Pass is
                // invisible (nothing casts/animates), yet leyline stops at EVERY
                // phase where a discretionary action (e.g. a castable instant in
                // hand) exists. As the game races through phases that is a burst of
                // ActionsAvailableReqs, and the HTTP inject round-trip can't drain it
                // — a response goes stale and the game-loop parks. For a Pass on an
                // ActionsAvailableReq, apply it directly to the action bridge and kick
                // the async drive (the same executor path the host uses), skipping the
                // client round-trip entirely. Real/visible actions (cast, attack,
                // targets, modals) still inject via the bridge, so they animate.
                if (prompt.type == GREMessageType.ActionsAvailableReq_695e && proposal.intent == "pass") {
                    val actionBridge = gameBridge.seat(seatId).action
                    val pendingAction = actionBridge.getPending()
                    if (pendingAction != null && actionBridge.submitAction(pendingAction.actionId, PlayerAction.PassPriority)) {
                        gameBridge.autoAdvanceRequester?.invoke("autopush server-side pass")
                        log.info("autopush: {} -> pass applied server-side (no client round-trip)", prompt.type)
                        return@submit
                    }
                }

                val responses = proposal.responses
                if (responses.isEmpty()) {
                    log.debug("autopush: {} -> {} (no delivery messages)", prompt.type, proposal.intent)
                    return@submit
                }
                val baseline = gameBridge.messageCounter.responsesAccepted()
                // Delivery anchor. responsesAccepted() bumps when a correlated
                // response clears the envelope guard (respId == lastPromptMsgId),
                // NOT when the action is applied. For a priority (AAR) inject that
                // gap is a wedge: an unrelated accepted response — or our own inject
                // being dropped while a stale one clears the envelope — advances the
                // counter while the game-loop stays parked in awaitAction, and the
                // copilot would read LANDED and stop re-injecting. Anchor to the
                // pending action id we are answering; a bare counter bump cannot read
                // as landed unless that specific window actually advanced. Non-AAR
                // prompts have no priority-window anchor, so they keep the
                // counter-only signal.
                val answeredActionId =
                    if (prompt.type == GREMessageType.ActionsAvailableReq_695e) {
                        gameBridge
                            .seat(seatId)
                            .action
                            .getPending()
                            ?.actionId
                    } else {
                        null
                    }
                var attempt = 0
                while (attempt < MAX_INJECT_ATTEMPTS) {
                    attempt++
                    for (response in responses) inject(response)
                    when (awaitOutcome(baseline, prompt.msgId, answeredActionId)) {
                        Outcome.LANDED -> {
                            log.info("autopush: {} -> intent={} landed after {} attempt(s)", prompt.type, proposal.intent, attempt)
                            return@submit
                        }
                        // The host emitted a newer prompt without accepting this
                        // response: it is stale, re-injecting it only draws
                        // IllegalRequests. Abandon so the newer prompt's onPrompt
                        // (queued behind this task) runs instead of being blocked.
                        Outcome.SUPERSEDED -> {
                            log.info(
                                "autopush: {} intent={} superseded (prompt advanced past msgId {}), abandoning",
                                prompt.type,
                                proposal.intent,
                                prompt.msgId,
                            )
                            return@submit
                        }
                        Outcome.DROPPED ->
                            log.warn(
                                "autopush: {} intent={} not landed, re-inject {}/{}",
                                prompt.type,
                                proposal.intent,
                                attempt,
                                MAX_INJECT_ATTEMPTS,
                            )
                    }
                }
                log.warn(
                    "autopush: {} intent={} gave up after {} attempts (transport drop)",
                    prompt.type,
                    proposal.intent,
                    MAX_INJECT_ATTEMPTS,
                )
            }.onFailure { log.warn("autopush failed for {}: {}", prompt.type, it.message) }
        }
    }

    private enum class Outcome { LANDED, SUPERSEDED, DROPPED }

    /**
     * Poll for the response to land or the prompt to be superseded.
     *
     * LANDED requires the answered window to have actually advanced, not merely
     * that [MessageCounter.responsesAccepted] bumped — see [answeredActionId].
     * For an AAR inject the window advances when the pending priority action id
     * changes or clears; the counter bump alone (envelope-correlated but not
     * applied) is not enough. SUPERSEDED means a newer prompt was emitted, so
     * [promptMsgId] is no longer the one awaiting a response. Landing wins ties so
     * a normal two-round-trip advance (accept + re-prompt) reads as LANDED.
     */
    private fun awaitOutcome(
        baseline: Int,
        promptMsgId: Int,
        answeredActionId: String?,
    ): Outcome {
        repeat(LAND_POLL_CHECKS) {
            if (landed(baseline, answeredActionId)) return Outcome.LANDED
            if (gameBridge.messageCounter.lastPromptMsgId() > promptMsgId) return Outcome.SUPERSEDED
            Thread.sleep(LAND_POLL_MS)
        }
        return when {
            landed(baseline, answeredActionId) -> Outcome.LANDED
            gameBridge.messageCounter.lastPromptMsgId() > promptMsgId -> Outcome.SUPERSEDED
            else -> {
                // A counter bump that never advanced the window is the false-LANDED
                // the anchor guards against: surface it so the wedge is visible.
                if (answeredActionId != null && gameBridge.messageCounter.responsesAccepted() > baseline) {
                    log.warn(
                        "autopush: envelope accepted but priority window {} did not advance — re-injecting instead of parking",
                        answeredActionId.take(8),
                    )
                }
                Outcome.DROPPED
            }
        }
    }

    /**
     * True when the answered response has actually been applied. For a priority
     * (AAR) inject the pending action id must have changed or cleared; a bare
     * [MessageCounter.responsesAccepted] bump does not count. Non-AAR prompts have
     * no window anchor and fall back to the counter.
     *
     * Internal for direct testing — the end-to-end inject path depends on the AI
     * choosing a non-pass action, which is not deterministic in a unit test.
     */
    internal fun landed(
        baseline: Int,
        answeredActionId: String?,
    ): Boolean {
        if (gameBridge.messageCounter.responsesAccepted() <= baseline) return false
        if (answeredActionId == null) return true
        return gameBridge
            .seat(seatId)
            .action
            .getPending()
            ?.actionId != answeredActionId
    }

    /** POST response bytes to the bridge's typed /respond endpoint. Returns true on a 2xx. */
    private fun inject(hex: String): Boolean =
        runCatching {
            val conn =
                (URL("$bridgeUrl/respond").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 6000
                    setRequestProperty("Content-Type", "text/plain")
                }
            conn.outputStream.use { os: OutputStream -> os.write(hex.toByteArray()) }
            val code = conn.responseCode
            conn.inputStream.use { it.readBytes() }
            code in 200..299
        }.getOrElse {
            log.debug("autopush inject transport error: {}", it.message)
            false
        }

    fun shutdown() = exec.shutdownNow()
}
