package forge.nexus.game

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds [GameObjectInfo] protobuf messages from Forge [Card] instances.
 *
 * Static card data (types, colors, abilities, base P/T) comes from [CardDb].
 * This mapper adds live game state: current P/T, tapped, sickness, damage,
 * loyalty, combat state, and attachment info.
 *
 * Extracted from [StateMapper] for independent testability.
 */
object ObjectMapper {

    /** Offset added to source card IDs for stack ability instance IDs. */
    internal const val STACK_ABILITY_ID_OFFSET = 100_000

    /**
     * Build a [GameObjectInfo] for a card in a private/player zone (hand, library, graveyard).
     * Does not set combat state or attachment — those are battlefield-only concerns
     * handled by [applyCardFields] when called with bridge+game args.
     *
     * @param visibility [Visibility.Private] for hand cards, [Visibility.Public] for graveyard.
     */
    fun buildCardObject(
        card: Card,
        instanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        visibility: Visibility = Visibility.Private,
    ): GameObjectInfo {
        val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
        return CardDb.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(visibility)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .applyCardFields(card)
            .build()
    }

    /**
     * Build a [GameObjectInfo] for a card in a shared/public zone (battlefield, stack, exile).
     * Includes combat state and attachment info when bridge+game are provided.
     */
    fun buildSharedCardObject(
        card: Card,
        instanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        controllerSeatId: Int,
        bridge: GameBridge,
        game: Game,
    ): GameObjectInfo {
        val grpId = CardDb.lookupByName(card.name) ?: GameBridge.FALLBACK_GRPID
        return CardDb.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(controllerSeatId)
            .applyCardFields(card, bridge, game)
            .build()
    }

    /**
     * Build a [GameObjectInfo] for an ability on the stack.
     */
    fun buildAbilityObject(
        grpId: Int,
        instanceId: Int,
        ownerSeatId: Int,
    ): GameObjectInfo =
        CardDb.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Ability)
            .setZoneId(ZoneIds.STACK)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .setObjectSourceGrpId(grpId)
            .build()

    /**
     * Apply dynamic Forge game state onto a [GameObjectInfo.Builder] already enriched
     * with static card data from [CardDb.buildObjectInfo].
     *
     * Static fields (types, colors, abilities, base P/T) come from the client DB.
     * This method adds: live P/T, tapped, sickness, damage, loyalty, combat, attachment.
     */
    fun GameObjectInfo.Builder.applyCardFields(
        card: Card,
        bridge: GameBridge? = null,
        game: Game? = null,
    ): GameObjectInfo.Builder {
        val type = card.type

        // Live P/T from Forge (may differ from base due to buffs/counters)
        if (type.isCreature) {
            setPower(Int32Value.newBuilder().setValue(card.netPower))
            setToughness(Int32Value.newBuilder().setValue(card.netToughness))
        }

        // Permanent state — battlefield only
        if (card.isInZone(ForgeZoneType.Battlefield)) {
            setIsTapped(card.isTapped)
            if (type.isCreature) {
                setHasSummoningSickness(card.hasSickness())
                if (card.damage > 0) setDamage(card.damage)
            }
            if (type.isPlaneswalker) {
                setLoyalty(UInt32Value.newBuilder().setValue(card.currentLoyalty))
            }
        }

        // Attachment (Auras, Equipment)
        val attachedTo = card.attachedTo
        if (attachedTo != null && bridge != null) {
            setParentId(bridge.getOrAllocInstanceId(attachedTo.id))
        }

        // Combat state
        val combat = game?.phaseHandler?.combat
        if (combat != null && type.isCreature) {
            if (combat.isAttacking(card)) setAttackState(AttackState.Attacking)
            if (combat.isBlocking(card)) setBlockState(BlockState.Blocking)
        }

        return this
    }
}
