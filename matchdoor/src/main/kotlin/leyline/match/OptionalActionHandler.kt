package leyline.match

import leyline.bridge.ForgeCardId
import leyline.bridge.WebPlayerController
import leyline.game.GameBridge
import leyline.game.mapper.PromptIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles "you may" trigger decisions via OptionalActionMessage (GRE type 45).
 *
 * Lifecycle (mirrors [CombatHandler]'s damage assignment pattern):
 * 1. Engine thread calls [WebPlayerController.confirmTrigger] → sets
 *    [WebPlayerController.pendingOptionalAction] → blocks on CompletableFuture
 * 2. Auto-pass loop calls [checkPendingOptionalAction] → detects non-null →
 *    sends OptionalActionMessage to client → returns true (loop exits)
 * 3. Client responds with OptionalResp (AllowYes / CancelNo)
 * 4. [MatchHandler] dispatches to [onOptionalActionResp] → completes future →
 *    engine unblocks → ability resolves or is deleted
 */
class OptionalActionHandler(private val ops: SessionOps) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Check for a pending optional action decision. Called from auto-pass loop
     * after damage assignment check.
     *
     * @return true if an OptionalActionMessage was sent (caller should exit loop)
     */
    fun checkPendingOptionalAction(bridge: GameBridge): Boolean {
        val wpc = bridge.humanController ?: return false
        val prompt = wpc.pendingOptionalAction ?: return false

        log.info(
            "OptionalActionHandler: optional trigger pending for {}",
            prompt.hostCard?.name ?: "unknown",
        )
        sendOptionalActionMessage(bridge, prompt)
        return true
    }

    /**
     * Handle OptionalActionResp from client.
     */
    fun onOptionalActionResp(
        greMsg: ClientToGREMessage,
        bridge: GameBridge,
        autoPass: (GameBridge) -> Unit,
    ) {
        val wpc = bridge.humanController ?: run {
            log.warn("OptionalActionHandler: no humanController for OptionalActionResp")
            return
        }
        val prompt = wpc.pendingOptionalAction ?: run {
            log.warn("OptionalActionHandler: no pending prompt for OptionalActionResp")
            return
        }

        val resp = greMsg.optionalResp
        val accepted = resp.response == OptionResponse.AllowYes

        log.info(
            "OptionalActionHandler: {} responded {} for {}",
            if (accepted) "Accept" else "Decline",
            resp.response,
            prompt.hostCard?.name ?: "unknown",
        )

        prompt.future.complete(accepted)
        bridge.awaitPriority()
        autoPass(bridge)
    }

    // --- Private ---

    private fun sendOptionalActionMessage(
        bridge: GameBridge,
        prompt: WebPlayerController.OptionalActionPrompt,
    ) {
        val hostCard = prompt.hostCard
        if (hostCard == null) {
            log.warn("OptionalActionHandler: hostCard is null — cannot send OptionalActionMessage")
            prompt.future.complete(true) // auto-accept to avoid engine deadlock
            return
        }

        val sourceId = bridge.getOrAllocInstanceId(ForgeCardId(hostCard.id)).value

        val optionalMsg = OptionalActionMessage.newBuilder()
            .setSourceId(sourceId)
            .build()

        // TODO: shock land ETB needs promptId 2233 + ReplacementEffect pAnn with
        // allocated affectorId as sourceId. Currently uses generic prompt for all.
        val promptProto = Prompt.newBuilder()
            .setPromptId(PromptIds.OPTIONAL_ACTION)
            .addParameters(
                PromptParameter.newBuilder()
                    .setParameterName("CardId")
                    .setType(ParameterType.Number)
                    .setNumberValue(sourceId),
            )
            .build()

        // Bare GSM diff with pendingMessageCount=1 — signals the client that
        // OptionalActionMessage follows. Without this, the client may process
        // the preceding GSM before the prompt arrives.
        val prevGsId = ops.counter.currentGsId()
        val gsId = ops.counter.nextGsId()
        val pendingGsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gsId)
            .setPrevGameStateId(prevGsId)
            .setPendingMessageCount(1)
            .setUpdate(GameStateUpdate.SendAndRecord)
            .build()

        val gsmGre = ops.makeGRE(GREMessageType.GameStateMessage_695e, gsId, ops.counter.nextMsgId()) {
            it.gameStateMessage = pendingGsm
        }

        val optionalGre = ops.makeGRE(GREMessageType.OptionalActionMessage_695e, gsId, ops.counter.nextMsgId()) {
            it.optionalActionMessage = optionalMsg
            it.prompt = promptProto
            // Controls Cancel button visibility, NOT whether declining is allowed.
            // Player can always decline via CancelNo response regardless of this value.
            it.allowCancel = AllowCancel.No_a526
        }

        ops.sendBundledGRE(listOf(gsmGre, optionalGre))
    }
}
