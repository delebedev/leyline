package leyline.game.bundle

import forge.game.card.Card
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import leyline.game.state.MechanicSourceFacts

/** Live shell adapter for the mechanic-source values required by one closed frame. */
internal object MechanicSourceFactsCapture {
    fun capture(
        bridge: GameBridge,
        events: List<GameEvent>,
    ): MechanicSourceFacts {
        val sourceIds = linkedSetOf<ForgeCardId>()
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .filter { it.isTrigger || it.isAbility }
            .forEach { event ->
                sourceIds += event.cardId
                event.triggeringObjectCardId?.let(sourceIds::add)
            }

        val sourceZones = linkedMapOf<ForgeCardId, Int>()
        sourceIds.forEach { forgeCardId ->
            bridge.findCard(forgeCardId)?.let { sourceZones[forgeCardId] = zoneId(it, bridge) }
        }
        val tokenCreators = linkedMapOf<ForgeCardId, MechanicSourceFacts.TokenCreator>()
        events
            .filterIsInstance<GameEvent.TokenCreated>()
            .filter { it.sourceCardId == null }
            .forEach { event ->
                val ability = bridge.findCard(event.cardId)?.tokenSpawningAbility ?: return@forEach
                val host = ability.hostCard ?: return@forEach
                if (ability.isAbility && ability.id != 0) {
                    tokenCreators[event.cardId] =
                        MechanicSourceFacts.TokenCreator(
                            sourceForgeCardId = ForgeCardId(host.id),
                            sourceAbilityForgeId = ability.id,
                        )
                }
            }
        return MechanicSourceFacts(sourceZones, tokenCreators)
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun zoneId(
        card: Card,
        bridge: GameBridge,
    ): Int {
        val ownerSeat = card.owner?.let(bridge::seatOf)?.value ?: 1
        return when (card.zone?.zoneType) {
            ZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ZoneType.Stack -> ZoneIds.STACK
            ZoneType.Graveyard -> ZoneIds.graveyardOf(ownerSeat)
            ZoneType.Exile -> ZoneIds.EXILE
            ZoneType.Hand -> ZoneIds.handOf(ownerSeat)
            ZoneType.Library -> ZoneIds.libraryOf(ownerSeat)
            ZoneType.Command -> ZoneIds.COMMAND
            else -> ZoneIds.BATTLEFIELD
        }
    }
}
