package leyline.copilot

import kotlinx.serialization.Serializable
import leyline.bridge.types.SeatId
import leyline.config.MatchConfig
import leyline.game.data.CardRepository
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * One-shot consult against a serialized game state: hydrate a standalone game
 * from [wotc.mtgo.gre.external.messaging.Messages.GameStateMessage] via
 * [SnapshotHydration], answer the supplied pending prompt with the copilot
 * decision stack, and score the position with the Forge-AI heuristic
 * evaluator. The hydrated game lives only for the duration of the consult.
 */
object SnapshotConsult {
    /**
     * Consult the decision stack about [prompt] given [gsm]. Never throws for
     * decision-layer failures — those surface as an `unrealizable` proposal;
     * eval is null when the evaluator itself fails.
     */
    fun consult(
        gsm: wotc.mtgo.gre.external.messaging.Messages.GameStateMessage,
        prompt: GREToClientMessage?,
        seat: Int,
        cardRepository: CardRepository,
        matchConfig: MatchConfig = MatchConfig(),
    ): ConsultResponse {
        val hydrated = SnapshotHydration.hydrateWithReport(gsm, seat, cardRepository, matchConfig)
        val bridge = hydrated.bridge
        return try {
            syncLandDrop(bridge, prompt, seat)
            val service = CopilotProposalService(bridge, SeatId(seat))
            ConsultResponse(
                proposal = service.propose(prompt),
                eval = service.evaluate(),
                fidelity = hydrated.fidelity.forPrompt(prompt),
            )
        } finally {
            bridge.teardownResources()
        }
    }

    /**
     * Mirror the source game's land-drop state onto the hydrated game. The
     * prompt is the truth: an ActionsAvailableReq with no Play action means
     * the drop is spent this turn. Hydration resets it, and the Forge AI
     * proposes nothing castable while it still wants to play a land from
     * hand — every main-phase consult after the land drop would degrade to
     * a pass. Hydrated-game-only: the bridge is torn down after the consult.
     */
    private fun syncLandDrop(
        bridge: leyline.game.state.GameBridge,
        prompt: GREToClientMessage?,
        seat: Int,
    ) {
        if (prompt == null || !prompt.hasActionsAvailableReq()) return
        val landPlayOffered =
            prompt.actionsAvailableReq.actionsList.any { it.actionType == ActionType.Play_add3 }
        if (landPlayOffered) return
        bridge.getPlayer(SeatId(seat))?.let { player ->
            if (player.landsPlayedThisTurn == 0) player.setLandsPlayedThisTurn(1)
        }
    }
}

/** Consult result: the proposed response plus a position eval for the seat. */
@Serializable
data class ConsultResponse(
    val proposal: CopilotProposal,
    val eval: EvalScore? = null,
    val fidelity: SnapshotFidelityReport,
)

/** Forge-AI heuristic position score from the consulted seat's perspective. */
@Serializable
data class EvalScore(
    val value: Int,
    /** Score variant discounting creatures that cannot act yet. */
    val summonSick: Int,
)
