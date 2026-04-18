package leyline.game.mapper

import forge.game.player.Player
import leyline.bridge.ForgeCardId
import leyline.game.CardData
import leyline.game.EffectTracker
import leyline.game.GameBridge
import leyline.game.snapshot.GsmSnapshot
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(ZoneMapper::class.java)

    /** Offset added to source card IDs for stack ability instance IDs. */
    private val STACK_ABILITY_ID_OFFSET = ObjectMapper.STACK_ABILITY_ID_OFFSET

    // --- Snapshot-based player zones ---

    /**
     * Add hand, library, and optionally graveyard zones for a player from snapshot.
     *
     * Reads card lists from [snap]'s zones map (keyed by arena zone ID) and looks up
     * each Forge [Card] via [bridge.findCard]. Cards not resolved (null) are skipped.
     * When [gyZoneId] is null (e.g. deal-hand diff at mulligan time) no graveyard zone
     * is emitted. ObjectMapper still takes a live [Card]; per-card state migration is Task 6.
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
        gyZoneId: Int? = null,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        revealHand: Boolean = false,
    ) {
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId || revealHand
        val handVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val cardVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val handBuilder = ZoneInfo.newBuilder()
            .setZoneId(handZoneId).setType(ZoneType.Hand)
            .setOwnerSeatId(seatId).setVisibility(handVisibility)
            .addViewers(seatId)
        if (revealHand) handBuilder.addViewers(if (seatId == 1) 2 else 1)
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                buildPlayerCard(snap, fid, instanceId, handZoneId, seatId, bridge, cardVisibility, "hand")
                    ?.let { gameObjects.add(it) }
            }
        }
        zones.add(handBuilder.build())

        val revealLib = revealForSeat == seatId
        val libBuilder = ZoneInfo.newBuilder()
            .setZoneId(libZoneId).setType(ZoneType.Library)
            .setOwnerSeatId(seatId).setVisibility(Visibility.Hidden)
        for (fid in snap.zones[libZoneId]?.contents ?: emptyList()) {
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            libBuilder.addObjectInstanceIds(instanceId)
            if (revealLib) {
                buildPlayerCard(snap, fid, instanceId, libZoneId, seatId, bridge, Visibility.Private, "library")
                    ?.let { gameObjects.add(it.toBuilder().addViewers(seatId).build()) }
            }
        }
        zones.add(libBuilder.build())

        if (gyZoneId != null) {
            val gyBuilder = ZoneInfo.newBuilder()
                .setZoneId(gyZoneId).setType(ZoneType.Graveyard)
                .setOwnerSeatId(seatId).setVisibility(Visibility.Public)
            for (fid in snap.zones[gyZoneId]?.contents ?: emptyList()) {
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                gyBuilder.addObjectInstanceIds(instanceId)
                buildPlayerCard(snap, fid, instanceId, gyZoneId, seatId, bridge, Visibility.Public, "graveyard")
                    ?.let { gameObjects.add(it) }
            }
            zones.add(gyBuilder.build())
        }
    }

    /**
     * Build [GameObjectInfo] for a card in a player zone (hand/library/graveyard) from snapshot.
     * Returns null when the snapshot has no entry for [fid] (card not findable at capture time —
     * e.g. freshly-moved cards whose Forge IDs are not yet bridged). Callers skip nulls.
     */
    @Suppress("detekt:LongParameterList")
    private fun buildPlayerCard(
        snap: GsmSnapshot,
        fid: ForgeCardId,
        instanceId: Int,
        zoneId: Int,
        seatId: Int,
        bridge: GameBridge,
        visibility: Visibility,
        zoneName: String,
    ): GameObjectInfo? {
        val cardSnap = snap.objects[fid] ?: run {
            log.warn("no snapshot for {} card {} — skipping game object", zoneName, fid)
            return null
        }
        return ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, seatId, bridge, visibility)
    }

    // --- Snapshot-based shared zones ---

    /**
     * Add cards in a shared zone (Battlefield, Stack, Exile) from snapshot.
     *
     * Reads the card list from [snap]'s zones map for [arenaZoneId] and looks up
     * each Forge [Card] via [bridge.findCard]. Cards not resolved (null) are skipped.
     * [human] is needed to determine owner/controller seat.
     */
    // forgeZone: documents which Forge zone maps to arenaZoneId; unused — snapshot reads by arenaZoneId
    @Suppress("detekt:LongParameterList", "detekt:UnusedParameter")
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

        for (fid in snap.zones[arenaZoneId]?.contents ?: emptyList()) {
            val card = bridge.findCard(fid) ?: continue
            // Filter synthetic engine objects (DetachedCardEffect etc.) — not real cards
            if (card.gamePieceType != forge.card.GamePieceType.CARD && !card.isToken) continue
            val ownerSeatId = if (card.owner == human) 1 else 2
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            zoneBuilder.addObjectInstanceIds(instanceId)

            val cardSnap = snap.objects[fid] ?: run {
                log.warn("no snapshot for shared card {} in zone {} — skipping game object", fid, arenaZoneId)
                continue
            }
            gameObjects.add(
                ObjectMapper.buildFromSnapshot(cardSnap, instanceId, arenaZoneId, ownerSeatId, bridge, Visibility.Public, keywordSnapshot),
            )
        }
        zones.add(zoneBuilder.build())
    }

    /**
     * Add [GameObjectType.Ability] entries for stack items not already represented
     * as cards in the stack zone. Reads from [snap.stack] — no live Forge reference needed.
     *
     * Uses the source card's ID + [STACK_ABILITY_ID_OFFSET] for stable instance IDs
     * (same scheme as the legacy game-based variant).
     */
    internal fun addStackAbilitiesFromSnapshot(
        snap: GsmSnapshot,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
    ) {
        if (snap.stack.entries.isEmpty()) return

        val zoneBuilder = zones.find { it.zoneId == ZoneIds.STACK }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == ZoneIds.STACK }

        // Track which source cards are already in the zone (from addSharedZoneCardsFromSnapshot)
        val existingIds = zoneBuilder.objectInstanceIdsList.toSet()

        for (entry in snap.stack.entries) {
            val cardInstanceId = bridge.getOrAllocInstanceId(entry.forgeCardId).value
            // Skip if the source card is already represented in the stack zone
            if (cardInstanceId in existingIds) continue

            // Use a separate instance ID for the ability on the stack
            val abilityInstanceId = bridge.getOrAllocInstanceId(
                ForgeCardId(entry.forgeCardId.value + STACK_ABILITY_ID_OFFSET),
            ).value
            val grpId = entry.grpId.takeIf { it != 0 } ?: GameBridge.FALLBACK_GRPID

            zoneBuilder.addObjectInstanceIds(abilityInstanceId)
            gameObjects.add(ObjectMapper.buildAbilityObject(grpId, abilityInstanceId, entry.owner.value, bridge.cardProto))
        }
        zones.add(zoneBuilder.build())
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

    /**
     * Player zones for initial bundle: empty hand, full library, empty graveyard/sideboard.
     *
     * Pre-deal state: library zone shows all deck cards (hand + library zones combined,
     * since no cards have been dealt yet). Reads card lists from [snap].
     */
    internal fun addInitialPlayerZonesFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
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
        for (fid in snap.zones[libZoneId]?.contents ?: emptyList()) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(fid).value)
        }
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(fid).value)
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
