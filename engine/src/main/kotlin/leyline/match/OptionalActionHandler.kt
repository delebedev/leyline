package leyline.match

import leyline.bridge.handoff.BlockingInteraction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles "you may" trigger decisions via OptionalActionMessage (GRE type 45).
 *
 * Lifecycle (mirrors [CombatHandler]'s damage assignment pattern):
 * 1. Engine thread calls `PlayerController.confirmTrigger` → coordinator commits
 *    OptionalActionMessage output → blocks on its runtime answer.
 * 2. Auto-pass loop calls [checkPendingOptionalAction] and drains committed output.
 * 3. Client responds with OptionalResp (AllowYes / CancelNo)
 * 4. [MatchHandler] dispatches to [onOptionalActionResp] → submits the value →
 *    engine unblocks → ability resolves or is deleted
 */
class OptionalActionHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Check for a pending optional action decision. Called from auto-pass loop
     * after damage assignment check.
     *
     * @return true if an OptionalActionMessage was sent (caller should exit loop)
     */
    fun checkPendingOptionalAction(): Boolean {
        val pending = ctx.bridge.cutCoordinator.currentBlockingInteraction() ?: return false
        val prompt = pending.interaction as? BlockingInteraction.Optional ?: return false

        log.info(
            "OptionalActionHandler: optional trigger pending for {}",
            prompt.sourceId ?: "unknown",
        )
        drainCommittedInteraction()
        return true
    }

    /**
     * Handle OptionalActionResp from client.
     */
    fun onOptionalActionResp(
        greMsg: ClientToGREMessage,
        autoPass: () -> Unit,
    ) {
        val bridge = ctx.bridge
        val pending =
            bridge.cutCoordinator.currentBlockingInteraction()?.takeIf { it.interaction is BlockingInteraction.Optional } ?: run {
                log.warn("OptionalActionHandler: no pending prompt for OptionalActionResp")
                return
            }
        val prompt = pending.interaction as BlockingInteraction.Optional

        val resp = greMsg.optionalResp
        val accepted = resp.response == OptionResponse.AllowYes

        log.info(
            "OptionalActionHandler: {} responded {} for {}",
            if (accepted) "Accept" else "Decline",
            resp.response,
            prompt.sourceId ?: "unknown",
        )

        if (!bridge.cutCoordinator.submitOptionalAnswer(pending.interactionId, greMsg.gameStateId, accepted)) return
        bridge.prioritySignal.markPromptResolved()
        bridge.awaitActionPriority(counters.seatId)
        autoPass()
    }

    private fun drainCommittedInteraction() {
        ctx.bridge
            .playbackFor(counters.seatId)
            ?.drainQueue()
            .orEmpty()
            .forEach(sink::sendBundledGRE)
    }
}
