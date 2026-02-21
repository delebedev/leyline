package forge.nexus.game

import forge.game.Game
import forge.game.player.Player
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds [ZoneInfo] protobuf messages and populates zone card lists.
 *
 * Handles player zones (hand, library, graveyard, sideboard), shared zones
 * (battlefield, stack, exile), and stack abilities. Uses [ObjectMapper] for
 * card/ability object construction.
 *
 * Extracted from [StateMapper] for independent testability.
 */
object ZoneMapper {

    /** Offset added to source card IDs for stack ability instance IDs. */
    private val STACK_ABILITY_ID_OFFSET = ObjectMapper.STACK_ABILITY_ID_OFFSET

    // --- Player zones ---

    /**
     * Add hand, library, and graveyard zones for a player.
     * Hand includes GameObjectInfo only for the viewing seat (opponent sees face-down).
     */
    internal fun addPlayerZones(
        game: Game,
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        viewingSeatId: Int = 0,
    ) {
        if (player == null) return

        // Hand — objectInstanceIds always (for card count), GameObjectInfo only for viewer.
        // Real server omits GameObjectInfo for opponent's hand → renders face-down.
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId
        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
            .addViewers(seatId)
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, handZoneId, seatId))
            }
        }
        zones.add(handBuilder.build())

        // Library — instance IDs only (hidden)
        val lib = player.getZone(ForgeZoneType.Library)
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in lib.cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        zones.add(libBuilder.build())

        // Graveyard — visible
        val gy = player.getZone(ForgeZoneType.Graveyard)
        val gyBuilder = ZoneInfo.newBuilder()
            .setZoneId(gyZoneId).setType(ZoneType.Graveyard)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Public)
        for (card in gy.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            gyBuilder.addObjectInstanceIds(instanceId)
            gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, gyZoneId, seatId, Visibility.Public))
        }
        zones.add(gyBuilder.build())
    }

    /** Hand + library only (no graveyard) — used for deal-hand at mulligan time. */
    internal fun addHandAndLibrary(
        game: Game,
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        viewingSeatId: Int = 0,
    ) {
        if (player == null) return

        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
        // Real server only includes GameObjectInfo for the viewing seat's hand.
        // Opponent hand cards appear in objectInstanceIds (for count) but have
        // no GameObjectInfo — client renders them face-down.
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, handZoneId, seatId))
            }
        }
        handBuilder.addViewers(seatId)
        zones.add(handBuilder.build())

        val lib = player.getZone(ForgeZoneType.Library)
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in lib.cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        zones.add(libBuilder.build())
    }

    // --- Shared zones ---

    /**
     * Add cards in a shared zone (Battlefield, Stack, Exile) with full game state.
     * Appends objectInstanceIds to the already-added ZoneInfo and builds GameObjectInfo
     * with combat state and attachment info via [ObjectMapper.buildSharedCardObject].
     */
    internal fun addSharedZoneCards(
        game: Game,
        forgeZone: ForgeZoneType,
        arenaZoneId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
        ai: Player?,
    ) {
        // Find the zone builder we already added
        val zoneBuilder = zones.find { it.zoneId == arenaZoneId }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == arenaZoneId }

        val allCards = game.getCardsIn(forgeZone)
        for (card in allCards) {
            val ownerSeatId = if (card.owner == human) 1 else 2
            val controllerSeatId = if (card.controller == human) 1 else 2
            val instanceId = bridge.getOrAllocInstanceId(card.id)
            zoneBuilder.addObjectInstanceIds(instanceId)

            gameObjects.add(
                ObjectMapper.buildSharedCardObject(card, instanceId, arenaZoneId, ownerSeatId, controllerSeatId, bridge, game),
            )
        }
        zones.add(zoneBuilder.build())
    }

    /**
     * Add [GameObjectType.Ability] entries for stack items not already represented
     * as cards in the stack zone. Uses the stack instance's unique ID + offset for
     * stable instance IDs.
     */
    internal fun addStackAbilities(
        game: Game,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
    ) {
        val stack = game.getStack()
        if (stack.isEmpty) return

        val zoneBuilder = zones.find { it.zoneId == ZoneIds.STACK }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == ZoneIds.STACK }

        // Track which source cards are already in the zone (from addSharedZoneCards)
        val existingIds = zoneBuilder.objectInstanceIdsList.toSet()

        for (entry in stack) {
            val sourceCard = entry.sourceCard ?: continue
            val cardInstanceId = bridge.getOrAllocInstanceId(sourceCard.id)
            // Skip if the source card is already represented in the stack zone
            if (cardInstanceId in existingIds) continue

            // Use a separate instance ID for the ability on the stack
            val abilityInstanceId = bridge.getOrAllocInstanceId(sourceCard.id + STACK_ABILITY_ID_OFFSET)
            val ownerSeatId = if (sourceCard.owner == human) 1 else 2
            val grpId = CardDb.lookupByName(sourceCard.name) ?: GameBridge.FALLBACK_GRPID

            zoneBuilder.addObjectInstanceIds(abilityInstanceId)
            gameObjects.add(ObjectMapper.buildAbilityObject(grpId, abilityInstanceId, ownerSeatId))
        }
        zones.add(zoneBuilder.build())
    }

    // --- Initial game zones ---

    /** Player zones for initial bundle: empty hand, full library, empty graveyard/sideboard. */
    internal fun addInitialPlayerZones(
        player: Player?,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        sbZoneId: Int,
    ) {
        if (player == null) return
        // Hand — empty, with viewer
        zones.add(
            ZoneInfo.newBuilder().setZoneId(handZoneId).setType(ZoneType.Hand)
                .setOwnerSeatId(seatId).setVisibility(Visibility.Private).addViewers(seatId).build(),
        )
        // Library — all cards (hand + library combined = full deck, pre-deal)
        val libBuilder = ZoneInfo.newBuilder().setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in player.getZone(ForgeZoneType.Library).cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        for (card in player.getZone(ForgeZoneType.Hand).cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(card.id))
        }
        zones.add(libBuilder.build())
        // Graveyard — empty
        zones.add(makeZone(gyZoneId, ZoneType.Graveyard, seatId, Visibility.Public))
        // Sideboard — empty, with viewer
        zones.add(
            ZoneInfo.newBuilder().setZoneId(sbZoneId).setType(ZoneType.Sideboard)
                .setOwnerSeatId(seatId).setVisibility(Visibility.Private).addViewers(seatId).build(),
        )
    }

    // --- Helpers ---

    /** Build a basic ZoneInfo with no cards. */
    internal fun makeZone(zoneId: Int, type: ZoneType, ownerSeatId: Int, visibility: Visibility): ZoneInfo =
        ZoneInfo.newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(visibility)
            .build()

    /** Private zone with viewers=[ownerSeatId] (hand, sideboard). */
    internal fun makePrivateZone(zoneId: Int, type: ZoneType, ownerSeatId: Int): ZoneInfo =
        ZoneInfo.newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(Visibility.Private)
            .addViewers(ownerSeatId)
            .build()

    /** Returns the hand zone ID of the opponent, or 0 if viewingSeatId is 0 (no filtering). */
    internal fun opponentHandZone(viewingSeatId: Int): Int = when (viewingSeatId) {
        1 -> ZoneIds.P2_HAND
        2 -> ZoneIds.P1_HAND
        else -> 0
    }
}
