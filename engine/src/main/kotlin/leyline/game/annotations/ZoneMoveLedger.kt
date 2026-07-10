package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.mapping.ZoneIds

enum class TransferPlanOrigin {
    Event,
    SnapshotFallback,
}

data class ZoneMoveIntent(
    val move: ZoneMove,
    val category: TransferCategory,
    val sourceCardId: ForgeCardId?,
    val sourceAbilityForgeId: Int,
    val rootAbilityForgeId: Int = 0,
    val origin: TransferPlanOrigin = TransferPlanOrigin.Event,
) {
    fun matches(
        forgeCardId: ForgeCardId,
        srcZoneId: Int,
        destZoneId: Int,
    ): Boolean =
        move.cardId == forgeCardId &&
            move.from.matchesProtocolZone(srcZoneId) &&
            move.to.matchesProtocolZone(destZoneId)
}

/** Pure event-first classification of ordered Forge zone operations. */
object ZoneMoveLedger {
    fun fold(
        moves: List<ZoneMove>,
        events: List<GameEvent>,
    ): List<ZoneMoveIntent> =
        moves.sortedBy { it.order }.map { move ->
            val eventBacked = move.cause != null || hasSpecificOperationEvent(move.cardId, events)
            ZoneMoveIntent(
                move = move,
                category = category(move, events),
                sourceCardId = move.cause?.sourceCardId ?: sourceFromSpecificEvent(move.cardId, events),
                sourceAbilityForgeId = move.cause?.abilityForgeId ?: 0,
                rootAbilityForgeId = move.cause?.rootAbilityForgeId ?: 0,
                origin = if (eventBacked) TransferPlanOrigin.Event else TransferPlanOrigin.SnapshotFallback,
            )
        }

    @Suppress("CyclomaticComplexMethod")
    private fun category(
        move: ZoneMove,
        events: List<GameEvent>,
    ): TransferCategory {
        val cardId = move.cardId
        val cast = events.filterIsInstance<GameEvent.SpellCast>().firstOrNull { it.cardId == cardId }
        val resolved = events.filterIsInstance<GameEvent.SpellResolved>().firstOrNull { it.cardId == cardId }
        return when {
            events.any { it is GameEvent.LandPlayed && it.cardId == cardId } -> TransferCategory.PlayLand
            move.to == Zone.Stack && cast != null && !cast.isAbility -> TransferCategory.CastSpell
            move.from == Zone.Stack && resolved?.hasFizzled == true -> TransferCategory.Countered
            move.to == Zone.Exile -> TransferCategory.Exile
            move.from == Zone.Stack && resolved != null -> TransferCategory.Resolve
            events.any { it is GameEvent.LegendRuleDeath && it.cardId == cardId } -> TransferCategory.SbaLegendRule
            events.any { it is GameEvent.CardSurveiled && it.cardId == cardId } -> TransferCategory.Surveil
            events.any { it is GameEvent.CardDiscarded && it.cardId == cardId } -> TransferCategory.Discard
            events.any { it is GameEvent.CardMilled && it.cardId == cardId } -> TransferCategory.Mill
            events.any { it is GameEvent.CardBounced && it.cardId == cardId } -> TransferCategory.Bounce
            events.any { it is GameEvent.CardExiled && it.cardId == cardId } -> TransferCategory.Exile
            events.any { it is GameEvent.CardSacrificed && it.cardId == cardId } -> TransferCategory.Sacrifice
            events.any { it is GameEvent.CardDestroyed && it.cardId == cardId } -> TransferCategory.Destroy
            move.cause?.api == "Discard" -> TransferCategory.Discard
            move.cause?.api == "Mill" -> TransferCategory.Mill
            move.cause?.api == "Surveil" -> TransferCategory.Surveil
            move.cause?.api == "Draw" -> TransferCategory.Draw
            move.cause?.api == "Counter" -> TransferCategory.Countered
            move.cause?.api == "ChangeZone" && move.from == Zone.Library && move.to == Zone.Hand -> TransferCategory.Put
            move.from == Zone.Battlefield && move.to in setOf(Zone.Hand, Zone.Library) -> TransferCategory.Bounce
            move.from == Zone.Library && move.to == Zone.Hand -> TransferCategory.Draw
            move.from == Zone.Library && move.to == Zone.Battlefield -> TransferCategory.Search
            move.from == Zone.Library && move.to == Zone.Graveyard -> TransferCategory.Mill
            move.from in setOf(Zone.Graveyard, Zone.Exile) && move.to in setOf(Zone.Hand, Zone.Battlefield) ->
                TransferCategory.Return
            move.from == Zone.Sideboard && move.to == Zone.Hand -> TransferCategory.Put
            move.from == Zone.Exile && move.to == Zone.Graveyard -> TransferCategory.Put
            move.from == Zone.Hand && move.to == Zone.Stack -> TransferCategory.CastSpell
            move.from == Zone.Stack && move.to == Zone.Battlefield -> TransferCategory.Resolve
            move.from == Zone.Stack && move.to == Zone.Graveyard -> TransferCategory.Countered
            move.from == Zone.Battlefield && move.to == Zone.Graveyard -> TransferCategory.Destroy
            else -> TransferCategory.ZoneTransfer
        }
    }

    private fun sourceFromSpecificEvent(
        cardId: ForgeCardId,
        events: List<GameEvent>,
    ): ForgeCardId? =
        events.firstNotNullOfOrNull { event ->
            when (event) {
                is GameEvent.CardDestroyed -> event.sourceCardId.takeIf { event.cardId == cardId }
                is GameEvent.CardMilled -> event.sourceCardId.takeIf { event.cardId == cardId }
                is GameEvent.CardSurveiled -> event.sourceCardId.takeIf { event.cardId == cardId }
                else -> null
            }
        }

    private fun hasSpecificOperationEvent(
        cardId: ForgeCardId,
        events: List<GameEvent>,
    ): Boolean =
        events.any { event ->
            when (event) {
                is GameEvent.LandPlayed -> event.cardId == cardId
                is GameEvent.SpellCast -> event.cardId == cardId
                is GameEvent.SpellResolved -> event.cardId == cardId
                is GameEvent.LegendRuleDeath -> event.cardId == cardId
                is GameEvent.CardSurveiled -> event.cardId == cardId
                is GameEvent.CardDiscarded -> event.cardId == cardId
                is GameEvent.CardMilled -> event.cardId == cardId
                is GameEvent.CardBounced -> event.cardId == cardId
                is GameEvent.CardExiled -> event.cardId == cardId
                is GameEvent.CardSacrificed -> event.cardId == cardId
                is GameEvent.CardDestroyed -> event.cardId == cardId
                else -> false
            }
        }
}

private fun Zone.matchesProtocolZone(zoneId: Int): Boolean =
    when (this) {
        Zone.Hand -> zoneId == ZoneIds.P1_HAND || zoneId == ZoneIds.P2_HAND
        Zone.Library -> zoneId == ZoneIds.P1_LIBRARY || zoneId == ZoneIds.P2_LIBRARY
        Zone.Graveyard -> zoneId == ZoneIds.P1_GRAVEYARD || zoneId == ZoneIds.P2_GRAVEYARD
        Zone.Battlefield -> zoneId == ZoneIds.BATTLEFIELD
        Zone.Exile -> zoneId == ZoneIds.EXILE
        Zone.Stack -> zoneId == ZoneIds.STACK
        Zone.Command -> zoneId == ZoneIds.COMMAND
        Zone.Sideboard -> zoneId == ZoneIds.P1_SIDEBOARD || zoneId == ZoneIds.P2_SIDEBOARD
        Zone.Other -> false
    }
