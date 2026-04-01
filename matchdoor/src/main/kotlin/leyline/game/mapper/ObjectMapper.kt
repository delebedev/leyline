package leyline.game.mapper

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import leyline.DevCheck
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.CardProtoBuilder
import leyline.game.CardRepository
import leyline.game.EffectTracker
import leyline.game.GameBridge
import leyline.game.KeywordGrpIds
import leyline.game.TokenIdentityRegistry
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.card.CardType.CoreType as ForgeCoreType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds [GameObjectInfo] protobuf messages from Forge [Card] instances.
 *
 * Static card data (types, colors, abilities, base P/T) comes from [CardProtoBuilder].
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
        bridge: GameBridge,
        visibility: Visibility = Visibility.Private,
    ): GameObjectInfo {
        val grpId = resolveGrpId(card, bridge.cards, instanceId, bridge.tokenRegistry, bridge)
        return bridge.cardProto.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(visibility)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .setOthersideGrpId(resolveOthersideGrpId(card, bridge.cards))
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
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
    ): GameObjectInfo {
        val grpId = resolveGrpId(card, bridge.cards, instanceId, bridge.tokenRegistry, bridge)
        val extrinsicKws = keywordSnapshot[instanceId]
            ?.mapNotNull { KeywordGrpIds.forKeyword(it.keyword) }
            ?: emptyList()
        return bridge.cardProto.buildObjectInfo(grpId, extrinsicKeywordGrpIds = extrinsicKws)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(controllerSeatId)
            .setOthersideGrpId(resolveOthersideGrpId(card, bridge.cards))
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
        cardProto: CardProtoBuilder,
    ): GameObjectInfo =
        cardProto.buildObjectInfo(grpId)
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
     * with static card data from [CardProtoBuilder.buildObjectInfo].
     *
     * Static fields (colors, abilities, base P/T) come from the client DB.
     * This method overlays: live card types (continuous effects can add/remove types),
     * live P/T, tapped, sickness, damage, loyalty, combat, attachment.
     */
    fun GameObjectInfo.Builder.applyCardFields(
        card: Card,
        bridge: GameBridge? = null,
        game: Game? = null,
    ): GameObjectInfo.Builder {
        val type = card.type

        // Live card types from Forge — continuous effects (e.g. crew) can add/remove types
        overlayCardTypes(type)

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

        // Copy token identity — isCopy flag + source grpId
        if (card.isToken && card.copiedPermanent != null) {
            setIsCopy(true)
            setObjectSourceGrpId(grpId)
        }

        // Attachment (Auras, Equipment)
        val attachedTo = card.attachedTo
        if (attachedTo != null && bridge != null) {
            setParentId(bridge.getOrAllocInstanceId(ForgeCardId(attachedTo.id)).value)
        }

        // Combat state
        val combat = game?.phaseHandler?.combat
        if (combat != null && type.isCreature) {
            applyCombatState(card, combat, bridge)
        }

        return this
    }

    /** Apply attackState/blockState, attackInfo/blockInfo to a creature in combat. */
    private fun GameObjectInfo.Builder.applyCombatState(
        card: Card,
        combat: forge.game.combat.Combat,
        bridge: GameBridge?,
    ) {
        if (combat.isAttacking(card)) {
            setAttackState(AttackState.Attacking)
            val defender = combat.getDefenderByAttacker(card)
            if (defender != null) {
                val targetId = when (defender) {
                    is Player -> {
                        val p1 = bridge?.getPlayer(SeatId(1))
                        if (defender.id == p1?.id) 1 else 2
                    }
                    is Card -> bridge?.getOrAllocInstanceId(ForgeCardId(defender.id))?.value ?: 0
                    else -> 0
                }
                if (targetId > 0) {
                    setAttackInfo(AttackInfo.newBuilder().setTargetId(targetId))
                }
            }
            val band = combat.getBandOfAttacker(card)
            if (band != null) {
                val blocked = band.isBlocked()
                if (blocked == true) {
                    setBlockState(BlockState.Blocked)
                } else if (blocked == false) {
                    setBlockState(BlockState.Unblocked)
                }
            }
        }
        if (combat.isBlocking(card)) {
            setBlockState(BlockState.Blocking)
            val attackers = combat.getAttackersBlockedBy(card)
            if (attackers.isNotEmpty() && bridge != null) {
                setBlockInfo(
                    BlockInfo.newBuilder().apply {
                        for (atk in attackers) {
                            addAttackerIds(bridge.getOrAllocInstanceId(ForgeCardId(atk.id)).value)
                        }
                    },
                )
            }
        }
    }

    /**
     * Build a [GameObjectInfo] for echo-back GSMs during iterative combat declaration.
     *
     * Echo objects carry NO combat state (no attackState/blockState).
     * Only base card fields are included.
     * The client uses the DeclareAttackersReq/DeclareBlockersReq re-prompt
     * (not object state) to track provisional selections.
     */
    fun buildProvisionalCombatObject(
        card: Card,
        instanceId: Int,
        zoneId: Int,
        ownerSeatId: Int,
        controllerSeatId: Int,
        bridge: GameBridge,
    ): GameObjectInfo {
        val grpId = resolveGrpId(card, bridge.cards, instanceId, bridge.tokenRegistry, bridge)
        return bridge.cardProto.buildObjectInfo(grpId)
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(controllerSeatId)
            .setOthersideGrpId(resolveOthersideGrpId(card, bridge.cards))
            .applyCardFields(card, bridge, game = null) // echo objects carry no combat state
            .build()
    }

    /**
     * Build a [GameObjectInfo] for a RevealedCard proxy that mirrors a real hand card.
     *
     * Per wire recordings: proxy has `type = RevealedCard`, `visibility = Public`,
     * `zoneId = handZoneId` (overlays the hand zone, NOT the Revealed zone),
     * and `viewers = [seatId-of-viewer]`. Mirrors grpId, types, P/T from real card.
     */
    fun buildRevealedCardProxy(
        card: Card,
        proxyInstanceId: Int,
        handZoneId: Int,
        ownerSeatId: Int,
        viewerSeatId: Int,
        bridge: GameBridge,
    ): GameObjectInfo {
        val grpId = resolveGrpId(card, bridge.cards, proxyInstanceId, bridge.tokenRegistry, bridge)
        return bridge.cardProto.buildObjectInfo(grpId)
            .setInstanceId(proxyInstanceId)
            .setType(GameObjectType.RevealedCard)
            .setZoneId(handZoneId)
            .setVisibility(Visibility.Public)
            .setOwnerSeatId(ownerSeatId)
            .setControllerSeatId(ownerSeatId)
            .addViewers(viewerSeatId)
            .applyCardFields(card)
            .build()
    }

    /** Resolve the other face's grpId for DFC cards. Returns 0 for non-DFC. */
    internal fun resolveOthersideGrpId(card: Card, cards: CardRepository): Int {
        if (!card.isDoubleFaced) return 0
        val otherStateName = if (card.currentState.stateName == forge.card.CardStateName.Backside) {
            forge.card.CardStateName.Original
        } else {
            forge.card.CardStateName.Backside
        }
        val otherState = card.getState(otherStateName) ?: return 0
        return cards.findGrpIdByName(otherState.name) ?: 0
    }

    /** Forge CoreType → proto CardType mapping. */
    private val coreTypeToProto: Map<ForgeCoreType, CardType> = mapOf(
        ForgeCoreType.Artifact to CardType.Artifact_a80b,
        ForgeCoreType.Creature to CardType.Creature,
        ForgeCoreType.Enchantment to CardType.Enchantment,
        ForgeCoreType.Instant to CardType.Instant,
        ForgeCoreType.Land to CardType.Land_a80b,
        ForgeCoreType.Planeswalker to CardType.Planeswalker,
        ForgeCoreType.Sorcery to CardType.Sorcery,
        ForgeCoreType.Kindred to CardType.Kindred,
        ForgeCoreType.Battle to CardType.Battle,
    )

    /**
     * Overlay live card types from Forge onto the builder when they differ from the DB.
     *
     * Continuous effects (crew, animate, enchant) can add or remove types at runtime.
     * Only rebuilds when the live set differs from what the DB provided — no-op otherwise.
     */
    private fun GameObjectInfo.Builder.overlayCardTypes(type: forge.card.CardTypeView) {
        val liveTypes = type.coreTypes.mapNotNull { coreTypeToProto[it] }.toSortedSet(compareBy { it.number })
        val dbTypes = cardTypesList.toSortedSet(compareBy { it.number })
        if (liveTypes == dbTypes) return

        clearCardTypes()
        liveTypes.forEach { addCardTypes(it) }
    }

    /**
     * Resolve grpId for a card. Tokens use the [TokenIdentityRegistry] cache,
     * falling back to the standard lookup chain on first encounter.
     * Copy tokens (Forge `copiedPermanent != null`) use the source permanent's grpId.
     */
    internal fun resolveGrpId(
        card: Card,
        cards: CardRepository,
        instanceId: Int = 0,
        tokenRegistry: TokenIdentityRegistry? = null,
        bridge: GameBridge? = null,
    ): Int {
        if (card.isToken) {
            // 1. Registry cache — stable across diff ticks
            tokenRegistry?.resolve(instanceId)?.let {
                bridge?.ensureCardData(it)
                return it
            }

            // 2. Copy token — use source permanent's grpId
            val copiedPermanent = card.copiedPermanent
            if (copiedPermanent != null) {
                val sourceGrpId = cards.findGrpIdByNameAnyFace(copiedPermanent.name)
                    ?: run {
                        log.error("copy token grpId=0: source '{}' not in card DB", copiedPermanent.name)
                        return GameBridge.FALLBACK_GRPID
                    }
                if (instanceId != 0) tokenRegistry?.register(instanceId, sourceGrpId)
                bridge?.ensureCardData(sourceGrpId)
                return sourceGrpId
            }

            // 3. Standard token — AbilityIdToLinkedTokenGrpId lookup
            val tokenGrpId = resolveTokenGrpId(card, cards)
            if (tokenGrpId != null) {
                if (instanceId != 0) tokenRegistry?.register(instanceId, tokenGrpId)
                bridge?.ensureCardData(tokenGrpId)
                return tokenGrpId
            }
            log.error("token grpId=0 for '{}' (forgeId={})", card.name, card.id)
            DevCheck.fail { "token grpId=0 for '${card.name}' (forgeId=${card.id})" }
            return GameBridge.FALLBACK_GRPID
        }
        return cards.findGrpIdByName(card.name) ?: run {
            log.error("grpId=0 for card '{}' (forgeId={}): not in client card DB", card.name, card.id)
            DevCheck.fail { "grpId=0 for '${card.name}' (forgeId=${card.id}): not in client card DB" }
            GameBridge.FALLBACK_GRPID
        }
    }

    /** Resolve token grpId via source card's AbilityIdToLinkedTokenGrpId mapping. */
    private fun resolveTokenGrpId(card: Card, cards: CardRepository): Int? {
        val sourceCard = card.tokenSpawningAbility?.hostCard ?: return null
        // Try current state name first (e.g. "Pest Problem" for adventure on stack),
        // then primary face name as fallback. Token mappings in Arena DB can be on
        // either face — adventure tokens map from the adventure face grpId.
        val sourceGrpId = cards.findGrpIdByNameAnyFace(sourceCard.name)
            ?: return null
        return cards.tokenGrpIdForCard(sourceGrpId, card.name)
    }
}
