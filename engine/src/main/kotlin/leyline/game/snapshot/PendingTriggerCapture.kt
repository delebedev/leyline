package leyline.game.snapshot

import forge.game.Game
import forge.game.card.Card
import forge.game.trigger.Trigger
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge

/** Captures delayed effects that need client-visible trigger-holder state. */
internal object PendingTriggerCapture {
    fun run(
        game: Game,
        bridge: GameBridge,
    ): List<PendingTriggerSnapshot> {
        val delayedTriggers = game.triggerHandler.delayedTriggersSnapshot
        val pending = delayedTriggers.mapNotNull { trigger -> captureDelayedTrigger(trigger, bridge) }
        bridge.retainPendingTriggerAbilityIdentities(delayedTriggers.map { it.id }.toSet())
        return pending + captureParadigmTriggers(game, bridge)
    }

    private fun captureDelayedTrigger(
        trigger: Trigger,
        bridge: GameBridge,
    ): PendingTriggerSnapshot? {
        val spawningAbility = trigger.spawningAbility ?: return null
        val sourceCard = spawningAbility.hostCard?.effectSource ?: spawningAbility.hostCard ?: return null
        val sourceForgeCardId = ForgeCardId(sourceCard.id)
        val sourceGrpId = bridge.cardRepository.findGrpIdByName(sourceCard.name) ?: return null
        val policy =
            if (spawningAbility.isWarp) {
                PendingTriggerVisualPolicy.warp
            } else {
                PendingTriggerVisualPolicy.forSourceCard(sourceGrpId)
            } ?: return null
        val sourceAbilityGrpId =
            bridge.resolvePendingTriggerAbilityIdentity(trigger.id, sourceForgeCardId) {
                bridge.resolveAbilityIdentity(sourceCard, spawningAbility.rootAbility)?.abilityGrpId
                    ?: soleTriggeredAbilityGrpId(sourceGrpId, bridge.cardRepository)
            } ?: return null
        bridge.recordPendingTriggerCleanupIdentity(trigger.id, policy.cleanupAbilityGrpId)
        val affectedCardIds =
            trigger.triggerRemembered
                .filterIsInstance<Card>()
                .map { ForgeCardId(it.id) }
                .distinct()
        if (affectedCardIds.isEmpty()) return null
        val owner = bridge.seatOf(spawningAbility.activatingPlayer ?: sourceCard.controller) ?: SeatId(1)
        return PendingTriggerSnapshot(
            runtimeTriggerId = trigger.id,
            holderForgeId = ForgeCardId(trigger.id + GameBridge.PENDING_TRIGGER_HOLDER_FORGE_OFFSET),
            owner = owner,
            sourceForgeCardId = sourceForgeCardId,
            parentInstanceId = sourceParentInstanceId(sourceForgeCardId, bridge),
            holderObjectSourceGrpId = if (policy.holderUsesSourceCardGrpId) sourceGrpId else sourceAbilityGrpId,
            cleanupAbilityGrpId = policy.cleanupAbilityGrpId,
            affectedCardIds = affectedCardIds,
            displaysAffectedCards = policy.displaysAffectedCards,
            removesFromZone = policy.removesFromZone,
            emitsTemporaryPermanent = policy.emitsTemporaryPermanent,
        )
    }

    private fun sourceParentInstanceId(
        sourceForgeCardId: ForgeCardId,
        bridge: GameBridge,
    ): Int =
        bridge
            .getInstanceIdMap()
            .entries
            .filter { (iid, fid) ->
                fid == sourceForgeCardId && bridge.getPreviousZone(iid) == ZoneIds.BATTLEFIELD
            }.maxByOrNull { (iid, _) -> iid.value }
            ?.key
            ?.value
            ?: bridge.getOrAllocInstanceId(sourceForgeCardId).value

    private fun soleTriggeredAbilityGrpId(
        cardGrpId: Int,
        cards: CardRepository,
    ): Int? {
        val candidates =
            cards.findByGrpId(cardGrpId)?.abilityIds.orEmpty().mapNotNull { (abilityGrpId, _) ->
                abilityGrpId.takeIf { cards.findAbilityInfo(it)?.category == 2 }
            }
        return candidates.singleOrNull()
    }

    private fun captureParadigmTriggers(
        game: Game,
        bridge: GameBridge,
    ): List<PendingTriggerSnapshot> =
        game.getCardsIn(ZoneType.Command).mapNotNull { effect ->
            val source = effect.effectSource?.takeIf { it.hasKeyword("Paradigm") } ?: return@mapNotNull null
            val sourceForgeCardId = ForgeCardId(source.id)
            val sourceGrpId = bridge.cardRepository.findGrpIdByName(source.name) ?: return@mapNotNull null
            val parentInstanceId =
                bridge.paradigmSourceStackIidFor(sourceForgeCardId)
                    ?: bridge.getOrAllocInstanceId(sourceForgeCardId).value
            PendingTriggerSnapshot(
                holderForgeId = ForgeCardId(effect.id + GameBridge.PARADIGM_TRIGGER_HOLDER_FORGE_OFFSET),
                owner = bridge.seatOf(source.controller) ?: SeatId(1),
                sourceForgeCardId = sourceForgeCardId,
                parentInstanceId = parentInstanceId,
                holderObjectSourceGrpId = sourceGrpId,
                cleanupAbilityGrpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
            )
        }
}
