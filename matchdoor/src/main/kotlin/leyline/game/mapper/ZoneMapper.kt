package leyline.game.mapper

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbilityStackInstance
import leyline.DevCheck
import leyline.bridge.ForgeCardId
import leyline.game.CardData
import leyline.game.EffectTracker
import leyline.game.GameBridge
import leyline.game.snapshot.GsmSnapshot
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
    @Suppress("detekt:LongParameterList")
    internal fun addPlayerZones(
        player: Player,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        revealHand: Boolean = false,
    ) {
        // Hand — objectInstanceIds always (for card count), GameObjectInfo only for viewer.
        // Client expects no GameObjectInfo for opponent's hand → renders face-down.
        // Exception: during reveal-choose, opponent's hand becomes Public with viewers=[1,2].
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId || revealHand
        val hand = player.getZone(ForgeZoneType.Hand)
        val handVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(handVisibility)
            .addViewers(seatId)
        if (revealHand) {
            // During reveal, both players see the hand
            val viewerSeat = if (seatId == 1) 2 else 1
            handBuilder.addViewers(viewerSeat)
        }
        val cardVisibility = if (revealHand) Visibility.Public else Visibility.Private
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, handZoneId, seatId, bridge, cardVisibility))
            }
        }
        zones.add(handBuilder.build())

        // Library — instance IDs always; full GameObjectInfo only during search (revealForSeat).
        val revealLib = revealForSeat == seatId
        val lib = player.getZone(ForgeZoneType.Library)
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in lib.cards) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            libBuilder.addObjectInstanceIds(instanceId)
            if (revealLib) {
                val obj = ObjectMapper.buildCardObject(card, instanceId, libZoneId, seatId, bridge, Visibility.Private)
                    .toBuilder().addViewers(seatId).build()
                gameObjects.add(obj)
            }
        }
        zones.add(libBuilder.build())

        // Graveyard — visible
        val gy = player.getZone(ForgeZoneType.Graveyard)
        val gyBuilder = ZoneInfo.newBuilder()
            .setZoneId(gyZoneId).setType(ZoneType.Graveyard)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Public)
        for (card in gy.cards) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            gyBuilder.addObjectInstanceIds(instanceId)
            gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, gyZoneId, seatId, bridge, Visibility.Public))
        }
        zones.add(gyBuilder.build())
    }

    /** Hand + library only (no graveyard) — used for deal-hand at mulligan time. */
    internal fun addHandAndLibrary(
        player: Player,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        viewingSeatId: Int = 0,
    ) {
        val hand = player.getZone(ForgeZoneType.Hand)
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Private)
        // Client only expects GameObjectInfo for the viewing seat's hand.
        // Opponent hand cards appear in objectInstanceIds (for count) but have
        // no GameObjectInfo — client renders them face-down.
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId
        for (card in hand.cards) {
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, handZoneId, seatId, bridge))
            }
        }
        handBuilder.addViewers(seatId)
        zones.add(handBuilder.build())

        val lib = player.getZone(ForgeZoneType.Library)
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in lib.cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value)
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
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
    ) {
        // Find the zone builder we already added
        val zoneBuilder = zones.find { it.zoneId == arenaZoneId }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == arenaZoneId }

        // Filter synthetic engine objects (DetachedCardEffect etc.) — not real cards
        val allCards = game.getCardsIn(forgeZone)
            .filter { it.gamePieceType == forge.card.GamePieceType.CARD || it.isToken }
        for (card in allCards) {
            val ownerSeatId = if (card.owner == human) 1 else 2
            val controllerSeatId = if (card.controller == human) 1 else 2
            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            zoneBuilder.addObjectInstanceIds(instanceId)

            gameObjects.add(
                ObjectMapper.buildSharedCardObject(card, instanceId, arenaZoneId, ownerSeatId, controllerSeatId, bridge, game, keywordSnapshot),
            )
        }
        zones.add(zoneBuilder.build())
    }

    // --- Snapshot-based player zones ---

    /**
     * Snapshot-based equivalent of [addPlayerZones].
     *
     * Reads card lists from [snap]'s zones map (keyed by arena zone ID) and looks up
     * each Forge [Card] via [bridge.findCard]. Cards not resolved (null) are skipped —
     * same behaviour as legacy when a card isn't found. ObjectMapper still takes a
     * live [Card]; per-card state migration is Task 6.
     */
    @Suppress("detekt:LongParameterList")
    internal fun addPlayerZonesFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        revealHand: Boolean = false,
    ) {
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId || revealHand
        val handVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(handVisibility)
            .addViewers(seatId)
        if (revealHand) {
            val viewerSeat = if (seatId == 1) 2 else 1
            handBuilder.addViewers(viewerSeat)
        }
        val cardVisibility = if (revealHand) Visibility.Public else Visibility.Private
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            val card = bridge.findCard(fid) ?: continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, handZoneId, seatId, bridge, cardVisibility))
            }
        }
        zones.add(handBuilder.build())

        val revealLib = revealForSeat == seatId
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (fid in snap.zones[libZoneId]?.contents ?: emptyList()) {
            val card = bridge.findCard(fid) ?: continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            libBuilder.addObjectInstanceIds(instanceId)
            if (revealLib) {
                val obj = ObjectMapper.buildCardObject(card, instanceId, libZoneId, seatId, bridge, Visibility.Private)
                    .toBuilder().addViewers(seatId).build()
                gameObjects.add(obj)
            }
        }
        zones.add(libBuilder.build())

        val gyBuilder = ZoneInfo.newBuilder()
            .setZoneId(gyZoneId).setType(ZoneType.Graveyard)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Public)
        for (fid in snap.zones[gyZoneId]?.contents ?: emptyList()) {
            val card = bridge.findCard(fid) ?: continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            gyBuilder.addObjectInstanceIds(instanceId)
            gameObjects.add(ObjectMapper.buildCardObject(card, instanceId, gyZoneId, seatId, bridge, Visibility.Public))
        }
        zones.add(gyBuilder.build())
    }

    // --- Snapshot-based shared zones ---

    /**
     * Snapshot-based equivalent of [addSharedZoneCards].
     *
     * Reads the card list from [snap]'s zones map for [arenaZoneId] and looks up
     * each Forge [Card] via [bridge.findCard]. Cards not resolved (null) are skipped.
     * The [forgeZone] and [human] params are retained for signature parity with the
     * legacy method; [human] is still needed to determine owner/controller seat.
     */
    @Suppress("detekt:LongParameterList", "detekt:UnusedParameter") // forgeZone kept for signature parity with legacy addSharedZoneCards; used by T5+
    internal fun addSharedZoneCardsFromSnapshot(
        snap: GsmSnapshot,
        forgeZone: ForgeZoneType,
        arenaZoneId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        human: Player?,
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
    ) {
        val zoneBuilder = zones.find { it.zoneId == arenaZoneId }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == arenaZoneId }

        val game = bridge.getGame() ?: run {
            zones.add(zoneBuilder.build())
            return
        }
        for (fid in snap.zones[arenaZoneId]?.contents ?: emptyList()) {
            val card = bridge.findCard(fid) ?: continue
            // Filter synthetic engine objects (DetachedCardEffect etc.) — not real cards
            if (card.gamePieceType != forge.card.GamePieceType.CARD && !card.isToken) continue
            val ownerSeatId = if (card.owner == human) 1 else 2
            val controllerSeatId = if (card.controller == human) 1 else 2
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            zoneBuilder.addObjectInstanceIds(instanceId)

            gameObjects.add(
                ObjectMapper.buildSharedCardObject(card, instanceId, arenaZoneId, ownerSeatId, controllerSeatId, bridge, game, keywordSnapshot),
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
            val cardInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(sourceCard.id)).value
            // Skip if the source card is already represented in the stack zone
            if (cardInstanceId in existingIds) continue

            // Use a separate instance ID for the ability on the stack
            val abilityInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(sourceCard.id + STACK_ABILITY_ID_OFFSET)).value
            val ownerSeatId = if (sourceCard.owner == human) 1 else 2
            val grpId = resolveStackAbilityGrpId(entry, sourceCard, bridge)
                ?: GameBridge.FALLBACK_GRPID

            zoneBuilder.addObjectInstanceIds(abilityInstanceId)
            gameObjects.add(ObjectMapper.buildAbilityObject(grpId, abilityInstanceId, ownerSeatId, bridge.cardProto))
        }
        zones.add(zoneBuilder.build())
    }

    /**
     * Resolve the grpId for a stack ability object.
     *
     * Multi-ability cards (Sagas, planeswalkers, modal triggers) have per-ability
     * grpIds in the Arena client DB. When a chapter trigger or similar
     * sub-ability is on the stack, we need the specific ability's grpId, not the
     * host card's. Saga example: Tribute to Horobi (79552) — Ch I→147926,
     * Ch II→147927, Ch III→147760.
     *
     * Falls back to the source card's grpId when sub-ability resolution doesn't
     * apply (plain spell cast, activated ability without a distinct grpId, DB
     * entry missing).
     */
    internal fun resolveStackAbilityGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        bridge: GameBridge,
    ): Int? {
        resolveChapterAbilityGrpId(entry, sourceCard, bridge)?.let { return it }
        return DevCheck.requireOrNull(bridge.cardRepository.findGrpIdByName(sourceCard.name)) {
            "stack ability grpId miss: '${sourceCard.name}'"
        }
    }

    /**
     * If [entry] is a Saga chapter trigger, return the chapter-specific ability
     * grpId from the source card's [CardData].
     *
     * Resolution order:
     *   1. [CardData.chapterAbilityGrpIds] — populated by [AbilityIdDeriver] from
     *      live Forge triggers. Always correct when present (tests, puzzles, prod
     *      once `ExposedCardRepository` is taught to populate it).
     *   2. Fall back to positional lookup in [CardData.abilityIds] — covers the
     *      current production shape where Arena's SQLite `Cards.AbilityIds`
     *      column lists chapter grpIds at the leading positions.
     *
     * Returns null for non-chapter triggers or when both lookups miss.
     */
    private fun resolveChapterAbilityGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        bridge: GameBridge,
    ): Int? {
        if (!entry.isTrigger) return null
        val sa = entry.spellAbility ?: return null
        val trigger = sa.trigger ?: return null
        val chapterParam = trigger.getParam("Chapter") ?: return null
        val chapterIdx = chapterParam.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val sourceGrpId = bridge.cardRepository.findGrpIdByName(sourceCard.name) ?: return null
        val cardData = bridge.cardRepository.findByGrpId(sourceGrpId) ?: return null
        return chapterGrpIdFromCardData(cardData, chapterIdx)
    }

    /**
     * Pick the chapter-specific ability grpId from a [CardData], independent of
     * the Forge stack entry. Extracted for unit-testability of both resolver
     * paths (populated [CardData.chapterAbilityGrpIds] vs positional fallback
     * via [CardData.abilityIds]).
     */
    internal fun chapterGrpIdFromCardData(cardData: CardData, chapterIdx: Int): Int? {
        cardData.chapterAbilityGrpIds.getOrNull(chapterIdx - 1)?.let { return it }
        return cardData.abilityIds.getOrNull(chapterIdx - 1)?.first
    }

    // --- Initial game zones ---

    /** Player zones for initial bundle: empty hand, full library, empty graveyard/sideboard. */
    internal fun addInitialPlayerZones(
        player: Player,
        seatId: Int,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        sbZoneId: Int,
    ) {
        // Hand — empty, with viewer
        zones.add(
            ZoneInfo.newBuilder().setZoneId(handZoneId).setType(ZoneType.Hand)
                .setOwnerSeatId(seatId).setVisibility(Visibility.Private).addViewers(seatId).build(),
        )
        // Library — all cards (hand + library combined = full deck, pre-deal)
        val libBuilder = ZoneInfo.newBuilder().setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (card in player.getZone(ForgeZoneType.Library).cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value)
        }
        for (card in player.getZone(ForgeZoneType.Hand).cards) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value)
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
