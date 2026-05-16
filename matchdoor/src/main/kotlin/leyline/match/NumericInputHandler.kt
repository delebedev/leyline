package leyline.match

import leyline.bridge.forge.PlayerController
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles numeric-input prompts (`Cost$ X`, `Announce$ X`, "choose X") via
 * `NumericInputReq` (GRE type 43).
 *
 * Lifecycle (mirrors [OptionalActionHandler]):
 * 1. Engine thread calls one of [PlayerController]'s `chooseNumber` overrides →
 *    [leyline.bridge.handoff.NumericInputGate.await] sets
 *    [PlayerController.pendingNumericInput] → blocks on `CompletableFuture<Int>`.
 * 2. Auto-pass loop calls [checkPendingNumericInput] → detects non-null →
 *    sends `NumericInputReq` (with a bare GSM diff carrying `pendingMessageCount=1`)
 *    → returns true (loop exits).
 * 3. Client responds with `NumericInputResp{numericInputValue: N}`. The response
 *    body is empty (`{}`) when `N == 0` due to proto3 default-omission — wire-
 *    equivalent to `numericInputValue: 0`.
 * 4. [MatchHandler] dispatches to [onNumericInputResp] → completes future →
 *    engine unblocks → cost/announce resolves with the chosen value.
 *
 * v1 only emits `numericInputType = ChooseX_ad80` with `maxValue = INT_MAX` and
 * `stepSize = 1`. ChooseAnyAmount and ChooseDieRoll variants need separate emit
 * paths and are deferred — exercise via a puzzle fixture before extending.
 */
class NumericInputHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Check for a pending numeric-input prompt. Called from the auto-pass loop
     * adjacent to the OptionalAction check.
     *
     * @return true if a `NumericInputReq` was sent (caller should exit loop).
     */
    fun checkPendingNumericInput(): Boolean {
        val wpc = ctx.bridge.humanController ?: return false
        val prompt = wpc.pendingNumericInput ?: return false

        log.info(
            "NumericInputHandler: numeric input pending for {} (min={}, max={})",
            prompt.sourceCard?.name ?: "unknown",
            prompt.min,
            prompt.max,
        )
        sendNumericInputReq(prompt)
        return true
    }

    /**
     * Handle `NumericInputResp` from client. Treats absent and `0` identically
     * (proto3 default-omission ships `0` as an empty body).
     */
    fun onNumericInputResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val wpc =
            bridge.humanController ?: run {
                log.warn("NumericInputHandler: no humanController for NumericInputResp")
                return
            }
        val prompt =
            wpc.pendingNumericInput ?: run {
                log.warn("NumericInputHandler: no pending prompt for NumericInputResp")
                return
            }

        val value = greMsg.numericInputResp.numericInputValue

        log.info(
            "NumericInputHandler: client picked {} for {}",
            value,
            prompt.sourceCard?.name ?: "unknown",
        )

        prompt.future.complete(value)
        bridge.awaitPriority()
        autoPass()
    }

    // --- Private ---

    private fun sendNumericInputReq(prompt: PlayerController.NumericInputPrompt) {
        val bridge = ctx.bridge
        val sourceCard = prompt.sourceCard
        if (sourceCard == null) {
            log.warn("NumericInputHandler: sourceCard is null — defaulting to {}", prompt.min)
            prompt.future.complete(prompt.min)
            return
        }

        val sourceId = bridge.getOrAllocInstanceId(ForgeCardId(sourceCard.id)).value

        val req =
            NumericInputReq
                .newBuilder()
                .setMaxValue(prompt.max)
                .setStepSize(1)
                .setSourceId(sourceId)
                .setNumericInputType(NumericInputType.ChooseX_ad80)
                .also { if (prompt.min > 0) it.minValue = prompt.min }
                .build()

        val promptProto =
            Prompt
                .newBuilder()
                .setPromptId(PromptIds.NUMERIC_INPUT)
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(sourceId),
                ).build()

        // Bare GSM diff with pendingMessageCount=1 — same pattern as OptionalActionHandler.
        val prevGsId = counters.counter.lastGameStateGsId().takeIf { it > 0 } ?: counters.counter.currentGsId()
        val gsId = counters.counter.nextGsId()
        val pendingGsm =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gsId)
                .setPrevGameStateId(prevGsId)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .build()

        val gsmGre =
            sink.makeGRE(GREMessageType.GameStateMessage_695e, gsId, counters.counter.nextMsgId()) {
                it.gameStateMessage = pendingGsm
            }

        val numericGre =
            sink.makeGRE(GREMessageType.NumericInputReq_695e, gsId, counters.counter.nextMsgId()) {
                it.numericInputReq = req
                it.prompt = promptProto
                it.allowCancel = AllowCancel.No_a526
            }

        sink.sendBundledGRE(listOf(gsmGre, numericGre))
    }
}
