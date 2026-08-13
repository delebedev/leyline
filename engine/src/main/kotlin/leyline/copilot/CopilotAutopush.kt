package leyline.copilot

import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Applies computed prompt responses asynchronously with bounded retries. */
class CopilotAutopush(
    private val gameBridge: GameBridge,
    private val seatId: SeatId,
    private val bridgeUrl: String,
) {
    private val log = LoggerFactory.getLogger(CopilotAutopush::class.java)
    private val service = CopilotProposalService(gameBridge, seatId)

    // Serialized off the session thread to preserve prompt order.
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "copilot-autopush").apply { isDaemon = true } }

    private companion object {
        const val MAX_INJECT_ATTEMPTS = 6
        const val LAND_POLL_MS = 80L
        const val LAND_POLL_CHECKS = 8 // ~640ms per attempt to observe a landed response
    }

    /** Applies the response for [prompt] from the single delivery thread. */
    fun onPrompt(prompt: GREToClientMessage) {
        exec.submit {
            runCatching {
                // Older prompts cannot change the current state; skip them rather
                // than consuming retry capacity needed by the active prompt.
                if (gameBridge.messageCounter.lastPromptMsgId() > prompt.msgId) {
                    log.debug("autopush: {} msgId={} superseded at dequeue, skipping", prompt.type, prompt.msgId)
                    return@submit
                }
                val proposal = service.propose(prompt)

                // A priority pass changes only engine state, so apply it directly to
                // avoid a delivery backlog across consecutive empty windows.
                if (prompt.type == GREMessageType.ActionsAvailableReq_695e && proposal.intent == "pass") {
                    val actionBridge = gameBridge.seat(seatId).action
                    val pendingAction = actionBridge.getPending()
                    if (pendingAction != null && actionBridge.submitAction(pendingAction.actionId, PlayerAction.PassPriority)) {
                        gameBridge.messageCounter.markPromptHandled(prompt.msgId)
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
                // Priority windows need both acceptance and a changed pending action;
                // the acceptance counter alone does not prove the action advanced.
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
                        // A newer prompt makes this response stale; let its queued
                        // handler take over.
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
     * Priority prompts land only after their pending action changes or clears;
     * other prompts use the acceptance counter. A completed response wins a tie
     * with a newer prompt.
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
                // Surface acceptance without an action advance for diagnosis.
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
     * True once a response is accepted and, for priority, advances its pending action.
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

    /** Sends one serialized response. */
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
            conn.inputStream.use { input: InputStream -> input.readBytes() }
            code in 200..299
        }.getOrElse {
            log.debug("autopush inject transport error: {}", it.message)
            false
        }

    fun shutdown() {
        exec.shutdownNow()
    }
}
