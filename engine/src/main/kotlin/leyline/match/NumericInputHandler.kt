package leyline.match

import leyline.bridge.handoff.NumericInputPrompt
import leyline.game.bundle.PendingPromptPlan
import leyline.game.mapping.PromptIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles numeric-input prompts (`Cost$ X`, `Announce$ X`, "choose X") via
 * `NumericInputReq` (GRE type 43).
 *
 * Lifecycle (mirrors [OptionalActionHandler]):
 * 1. Engine thread calls one of `PlayerController`'s `chooseNumber` overrides →
 *    [leyline.bridge.handoff.NumericInputGate.await] sets
 *    `pendingNumericInput` → blocks on `CompletableFuture<Int>`.
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
        val prompt = ctx.bridge.pendingNumericInput() ?: return false

        log.info(
            "NumericInputHandler: numeric input pending for {} (min={}, max={})",
            prompt.sourceCardName ?: "unknown",
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
        val prompt =
            bridge.pendingNumericInput() ?: run {
                log.warn("NumericInputHandler: no pending prompt for NumericInputResp")
                return
            }

        val value = greMsg.numericInputResp.numericInputValue

        log.info(
            "NumericInputHandler: client picked {} for {}",
            value,
            prompt.sourceCardName ?: "unknown",
        )

        bridge.submitNumericInput(value)
        ctx.engine.awaitPriority()
        autoPass()
    }

    // --- Private ---

    private fun sendNumericInputReq(prompt: NumericInputPrompt) {
        val bridge = ctx.bridge
        val sourceCardId = prompt.sourceCardId
        if (sourceCardId == null) {
            log.warn("NumericInputHandler: sourceCard is null — defaulting to {}", prompt.min)
            bridge.submitNumericInput(prompt.min)
            return
        }

        val sourceId = bridge.getOrAllocInstanceId(sourceCardId).value

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

        sink.sendBundledGRE(
            PendingPromptPlan.build(
                counters.counter,
                counters.seatId,
                GREMessageType.NumericInputReq_695e,
            ) {
                it.numericInputReq = req
                it.prompt = promptProto
                it.allowCancel = AllowCancel.No_a526
            },
        )
    }
}
