package forge.nexus.game

import forge.game.Game
import forge.game.card.Card
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(ObjectMapper::class.java)

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
        val grpId = resolveGrpId(card)
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
        val grpId = resolveGrpId(card)
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

    /** Resolve grpId for a card, using token-specific lookup for tokens. */
    internal fun resolveGrpId(card: Card): Int {
        if (card.isToken) {
            return resolveTokenGrpId(card) ?: run {
                log.error("token grpId=0 for '{}' (forgeId={})", card.name, card.id)
                GameBridge.FALLBACK_GRPID
            }
        }
        return CardDb.lookupByName(card.name) ?: run {
            log.error("grpId=0 for card '{}' (forgeId={}): not in client card DB", card.name, card.id)
            GameBridge.FALLBACK_GRPID
        }
    }

    /** Resolve token grpId via source card's AbilityIdToLinkedTokenGrpId mapping. */
    private fun resolveTokenGrpId(card: Card): Int? {
        val sourceCard = card.tokenSpawningAbility?.hostCard ?: return null
        val sourceGrpId = CardDb.lookupByName(sourceCard.name) ?: return null
        return CardDb.tokenGrpIdForCard(sourceGrpId, card.name)
    }
}
