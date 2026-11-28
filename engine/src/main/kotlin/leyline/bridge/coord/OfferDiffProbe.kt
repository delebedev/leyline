package leyline.bridge.coord

import forge.game.Game
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.OfferDiff
import leyline.bridge.OfferDiffEntry
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.InstanceId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Runs [OfferDiff] for one published priority window and records the result in
 * [GameBridge.offerDiffDiagnostics].
 *
 * The comparison runs on the engine thread while Forge is blocked for this
 * window, against the same [PriorityActionCandidates] the window was built
 * from. A comparison that throws is recorded as a failure and never fails the
 * window.
 */
internal object OfferDiffProbe {
    private val log = LoggerFactory.getLogger(OfferDiffProbe::class.java)

    fun recordWindow(
        bridge: GameBridge,
        game: Game,
        pending: GameActionBridge.PendingAction,
        offers: List<GameActionBridge.ActionOffer>,
        messages: List<GREToClientMessage>,
    ) {
        val candidates = pending.priorityCandidates
        if (!bridge.engineSettings.dev.offerDiff || pending.state.kind != PendingActionKind.PRIORITY || candidates == null) return
        try {
            val player = game.players.first { it.id == pending.state.priorityPlayerId }
            val actions = messages.firstOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq
            bridge.offerDiffDiagnostics.record(compare(bridge, game, player, candidates, offers, actions))
        } catch (error: VirtualMachineError) {
            throw error
        } catch (ex: Throwable) {
            // A Forge Error (such as an assertion) is a diff failure, not a publication failure.
            log.warn("Offer diff failed: {}", ex.toString())
            bridge.offerDiffDiagnostics.recordFailure(ex)
        }
    }

    /** [OfferDiff.compare] with inactive actions resolved to Forge cards through [bridge]. */
    fun compare(
        bridge: GameBridge,
        game: Game,
        player: Player,
        candidates: PriorityActionCandidates,
        offers: List<GameActionBridge.ActionOffer>,
        actions: ActionsAvailableReq?,
    ): List<OfferDiffEntry> {
        val inactive =
            actions
                ?.inactiveActionsList
                .orEmpty()
                .flatMap { action ->
                    val cardId = bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return@flatMap emptyList()
                    val onBattlefield = bridge.findCard(cardId)?.isInZone(ZoneType.Battlefield) == true
                    kindsOf(action.actionType, onBattlefield).map {
                        OfferDiff.InactiveOffer(cardId.value, it, backFace = action.actionType == ActionType.PlayMdfc)
                    }
                }
        return OfferDiff.compare(game, player, candidates, offers, inactive)
    }

    /**
     * The Forge ability kinds an inactive action of [type] can stand for. A Room
     * door action is a spell cast from hand, and Forge's static unlock ability
     * once the Room is on the battlefield.
     */
    private fun kindsOf(
        type: ActionType,
        onBattlefield: Boolean,
    ): List<OfferDiff.Kind> =
        when {
            type in roomDoors -> listOf(if (onBattlefield) OfferDiff.Kind.Ability else OfferDiff.Kind.Spell)
            else -> inactiveKinds.filter { (_, types) -> type in types }.map { it.key }
        }

    private val roomDoors = setOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)

    private val inactiveKinds: Map<OfferDiff.Kind, Set<ActionType>> =
        mapOf(
            OfferDiff.Kind.Spell to
                setOf(
                    ActionType.Cast,
                    ActionType.CastLeft,
                    ActionType.CastRight,
                    ActionType.CastAdventure,
                    ActionType.CastMdfc,
                    ActionType.CastPrototype,
                    ActionType.CastOmen,
                ),
            OfferDiff.Kind.Land to setOf(ActionType.Play_add3, ActionType.PlayMdfc),
            OfferDiff.Kind.Mana to setOf(ActionType.ActivateMana),
            OfferDiff.Kind.Ability to setOf(ActionType.Activate_add3, ActionType.Special_add3, ActionType.SpecialTurnFaceUp_add3),
        )
}
