package leyline.copilot

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.Executors

/** Applies computed prompt responses asynchronously through exact client requests. */
class CopilotAutopush(
    private val gameBridge: GameBridge,
    private val seatId: SeatId,
    bridgeUrl: String,
    nativeReadyPollMs: Long = 250,
    nativeReadyChecks: Int = 60,
    private val landPollMs: Long = 80,
    private val landPollChecks: Int = 8,
) {
    private val log = LoggerFactory.getLogger(CopilotAutopush::class.java)
    private val service = CopilotProposalService(gameBridge, seatId)
    private val transport = CopilotNativeTransport(bridgeUrl, nativeReadyPollMs, nativeReadyChecks)

    // Serialized off the session thread to preserve prompt order.
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "copilot-autopush").apply { isDaemon = true } }

    /** Applies the response for [prompt] from the single delivery thread. */
    fun onPrompt(prompt: GREToClientMessage) {
        exec.submit {
            runCatching {
                // Older prompts cannot change the current state; skip them rather
                // than consuming retry capacity needed by the active prompt.
                if (gameBridge.committedSequence().lastPromptMsgId > prompt.msgId) {
                    log.debug("autopush: {} msgId={} superseded at dequeue, skipping", prompt.type, prompt.msgId)
                    return@submit
                }
                val proposal = service.propose(prompt)
                val responses = proposal.responses
                if (responses.isEmpty()) {
                    log.debug("autopush: {} -> {} (no delivery messages)", prompt.type, proposal.intent)
                    return@submit
                }
                if (responses.size > 1) {
                    log.debug(
                        "autopush: {} intent={} defers {} response(s) until the next prompt",
                        prompt.type,
                        proposal.intent,
                        responses.size - 1,
                    )
                }
                val baseline = gameBridge.responseAcceptance.responsesAccepted()
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
                val delivery =
                    transport.submit(prompt, responses.first()) {
                        gameBridge.committedSequence().lastPromptMsgId > prompt.msgId
                    }
                when (delivery.outcome) {
                    NativeSubmitOutcome.SUBMITTED ->
                        when (awaitOutcome(baseline, prompt.msgId, answeredActionId)) {
                            Outcome.LANDED -> {
                                log.info("autopush: {} -> intent={} landed", prompt.type, proposal.intent)
                                return@submit
                            }
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
                                    "autopush: {} intent={} submitted but not observed; refusing duplicate delivery",
                                    prompt.type,
                                    proposal.intent,
                                )
                        }
                    NativeSubmitOutcome.SUPERSEDED ->
                        log.debug("autopush: {} msgId={} superseded before submission", prompt.type, prompt.msgId)
                    NativeSubmitOutcome.NOT_READY,
                    NativeSubmitOutcome.IDENTITY_ERROR,
                    NativeSubmitOutcome.INVOKE_ERROR,
                    NativeSubmitOutcome.TRANSPORT_ERROR,
                    ->
                        log.warn(
                            "autopush: {} intent={} delivery failed: {} ({})",
                            prompt.type,
                            proposal.intent,
                            delivery.outcome,
                            delivery.detail,
                        )
                }
            }.onFailure { log.atWarn().setCause(it).log("autopush failed for {}", prompt.type) }
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
        repeat(landPollChecks) {
            if (landed(baseline, answeredActionId)) return Outcome.LANDED
            if (gameBridge.committedSequence().lastPromptMsgId > promptMsgId) return Outcome.SUPERSEDED
            Thread.sleep(landPollMs)
        }
        return when {
            landed(baseline, answeredActionId) -> Outcome.LANDED
            gameBridge.committedSequence().lastPromptMsgId > promptMsgId -> Outcome.SUPERSEDED
            else -> {
                if (answeredActionId != null && gameBridge.responseAcceptance.responsesAccepted() > baseline) {
                    log.warn(
                        "autopush: envelope accepted but priority window {} did not advance",
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
        if (gameBridge.responseAcceptance.responsesAccepted() <= baseline) return false
        if (answeredActionId == null) return true
        return gameBridge
            .seat(seatId)
            .action
            .getPending()
            ?.actionId != answeredActionId
    }

    fun shutdown() {
        exec.shutdownNow()
    }
}
