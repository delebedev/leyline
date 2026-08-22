package leyline.game.snapshot

import forge.game.Game
import forge.game.card.Card
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.data.CardRepository
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.game.zone.ZoneType as ForgeZoneType

internal object MutateSnapshotSupport {
    data class MergedState(
        val targetInstanceId: Int?,
        val componentAbilityGrpIds: List<Int>,
        val componentAbilityOriginalCardGrpIds: List<Int>,
        val isMergedPermanent: Boolean,
        val isTopComponent: Boolean,
    )

    fun captureMergedZone(
        game: Game,
        bridge: GameBridge,
    ): ZoneSnapshot =
        ZoneSnapshot(
            id = ZoneIds.SUPPRESSED,
            type = ZoneType.Suppressed,
            owner = null,
            visibility = Visibility.Public,
            contents = mergedCards(game, bridge).map { ForgeCardId(it.id) },
        )

    fun liveCardsByZoneId(
        game: Game,
        bridge: GameBridge,
    ): Map<Pair<Int, ForgeCardId>, Card> = mergedCards(game, bridge).associateBy { ZoneIds.SUPPRESSED to ForgeCardId(it.id) }

    fun mergedState(
        card: Card,
        bridge: GameBridge,
        repo: CardRepository,
    ): MergedState {
        val componentSources = componentAbilitySources(card, repo)
        return MergedState(
            targetInstanceId =
                card.getMergedToCard()?.let {
                    bridge.instanceId(it)
                },
            componentAbilityGrpIds = componentSources.map { it.abilityGrpId },
            componentAbilityOriginalCardGrpIds = componentSources.map { it.componentGrpId },
            isMergedPermanent = card.hasMergedCard(),
            isTopComponent = isTopComponent(card),
        )
    }

    private fun mergedCards(
        game: Game,
        bridge: GameBridge,
    ): List<Card> =
        buildList {
            addAll(game.getCardsIn(ForgeZoneType.Merged))
            for (seatNum in listOf(1, 2)) {
                val player = bridge.getPlayer(SeatId(seatNum)) ?: continue
                player.getZone(ForgeZoneType.Merged)?.cards?.let(::addAll)
            }
        }.distinctBy { it.id }
            .filter(::isSnapshotVisibleCard)

    private fun isSnapshotVisibleCard(card: Card): Boolean = !card.isImmutable() || card.getEffectSource() == null

    private data class ComponentAbilitySource(
        val abilityGrpId: Int,
        val componentGrpId: Int,
    )

    private fun componentAbilitySources(
        card: Card,
        repo: CardRepository,
    ): List<ComponentAbilitySource> {
        if (!card.hasMergedCard()) return emptyList()
        // The battlefield object keeps the target card's Forge id even when the
        // mutating component is on top; the current name is the visible face.
        val visibleGrpId = repo.findGrpIdByName(card.name)
        return card
            .getMergedCards()
            .flatMap { component ->
                val grpId = repo.findGrpIdByName(component.name) ?: return@flatMap emptyList()
                if (grpId == visibleGrpId) return@flatMap emptyList()
                repo
                    .findByGrpId(grpId)
                    ?.abilityIds
                    ?.map { ComponentAbilitySource(it.first, grpId) }
                    .orEmpty()
            }.distinct()
    }

    private fun isTopComponent(card: Card): Boolean {
        val mergedTo = card.getMergedToCard() ?: return false
        if (!mergedTo.hasMergedCard()) return false
        return mergedTo.getMergedCards().firstOrNull()?.id == card.id
    }
}
