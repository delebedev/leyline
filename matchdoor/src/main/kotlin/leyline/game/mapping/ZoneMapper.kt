package leyline.game.mapping

import forge.game.player.Player
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.data.CardData
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.EffectTracker
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds [ZoneInfo] protobuf messages and populates zone card lists.
 *
 * Handles player zones (hand, library, graveyard, sideboard), shared zones
 * (battlefield, stack, exile), and stack abilities. Uses [ObjectMapper] for
 * card/ability object construction.
 */
object ZoneMapper {
    private val log = LoggerFactory.getLogger(ZoneMapper::class.java)

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
        seatId: SeatId,
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
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId.value || revealHand
        val handVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val cardVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val handBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(handZoneId)
                .setType(ZoneType.Hand)
                .setOwnerSeatId(seatId.value)
                .setVisibility(handVisibility)
                .addViewers(seatId.value)
        if (revealHand) handBuilder.addViewers(seatId.opponent.value)
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                addPlayerCardObjects(snap, fid, instanceId, handZoneId, seatId, bridge, cardVisibility, "hand", gameObjects)
            }
        }
        zones.add(handBuilder.build())

        val revealLib = revealForSeat == seatId.value
        val libBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(libZoneId)
                .setType(ZoneType.Library)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Hidden)
        for (fid in snap.zones[libZoneId]?.contents ?: emptyList()) {
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            libBuilder.addObjectInstanceIds(instanceId)
            if (revealLib) {
                addPlayerCardObjects(
                    snap,
                    fid,
                    instanceId,
                    libZoneId,
                    seatId,
                    bridge,
                    Visibility.Private,
                    "library",
                    gameObjects,
                    addViewer = seatId.value,
                )
            }
        }
        zones.add(libBuilder.build())

        if (gyZoneId != null) {
            val gyBuilder =
                ZoneInfo
                    .newBuilder()
                    .setZoneId(gyZoneId)
                    .setType(ZoneType.Graveyard)
                    .setOwnerSeatId(seatId.value)
                    .setVisibility(Visibility.Public)
            for (fid in snap.zones[gyZoneId]?.contents ?: emptyList()) {
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                gyBuilder.addObjectInstanceIds(instanceId)
                addPlayerCardObjects(snap, fid, instanceId, gyZoneId, seatId, bridge, Visibility.Public, "graveyard", gameObjects)
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
        seatId: SeatId,
        bridge: GameBridge,
        visibility: Visibility,
        zoneName: String,
    ): GameObjectInfo? {
        val cardSnap =
            snap.objects[fid] ?: run {
                log.warn("no snapshot for {} card {} — skipping game object", zoneName, fid)
                return null
            }
        return ObjectMapper.buildFromSnapshot(
            cardSnap,
            instanceId,
            zoneId,
            seatId.value,
            bridge.cardProto,
            visibility,
            parentLinkage = snap.boundCards[fid]?.parentLinkage,
        )
    }

    @Suppress("detekt:LongParameterList")
    private fun addPlayerCardObjects(
        snap: GsmSnapshot,
        fid: ForgeCardId,
        instanceId: Int,
        zoneId: Int,
        seatId: SeatId,
        bridge: GameBridge,
        visibility: Visibility,
        zoneName: String,
        gameObjects: MutableList<GameObjectInfo>,
        addViewer: Int? = null,
    ) {
        val card =
            buildPlayerCard(snap, fid, instanceId, zoneId, seatId, bridge, visibility, zoneName)
                ?: return
        gameObjects.add(addViewer?.let { card.toBuilder().addViewers(it).build() } ?: card)
        addDisturbBackObject(snap, fid, instanceId, zoneId, seatId, bridge, visibility, gameObjects)
    }

    @Suppress("detekt:LongParameterList")
    private fun addDisturbBackObject(
        snap: GsmSnapshot,
        fid: ForgeCardId,
        sourceInstanceId: Int,
        zoneId: Int,
        seatId: SeatId,
        bridge: GameBridge,
        visibility: Visibility,
        gameObjects: MutableList<GameObjectInfo>,
    ) {
        val bound = snap.boundCards[fid] ?: return
        if (bound.altCost(KeywordAbilityIds.DISTURB) == null) return
        val cardSnap = snap.objects[fid] ?: return
        if (cardSnap.othersideGrpId == 0) return
        val backInstanceId = bridge.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(fid)).value
        gameObjects.add(
            ObjectMapper.buildDisturbBackObject(
                cardSnap,
                backInstanceId,
                sourceInstanceId,
                zoneId,
                seatId.value,
                bridge.cardProto,
                visibility,
            ),
        )
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

            val cardSnap =
                snap.objects[fid] ?: run {
                    log.warn("no snapshot for shared card {} in zone {} — skipping game object", fid, arenaZoneId)
                    continue
                }
            gameObjects.add(
                ObjectMapper.buildFromSnapshot(
                    cardSnap,
                    instanceId,
                    arenaZoneId,
                    ownerSeatId,
                    bridge.cardProto,
                    Visibility.Public,
                    keywordSnapshot,
                    parentLinkage = snap.boundCards[fid]?.parentLinkage,
                ),
            )
        }
        zones.add(zoneBuilder.build())
    }

    /**
     * Add [GameObjectType.Ability] entries for stack items not already represented
     * as cards in the stack zone. Reads from [snap.stack] — no live Forge reference needed.
     *
     * Mints iids via [FrameIdResolver.triggerStackAbilityForgeId] (SA-id-keyed
     * surrogate) so back-to-back triggers from one source card mint distinct
     * iids. Falls back to source-card-keyed surrogate when `entry.forgeAbilityId == 0`
     * — the synthetic-test path where the SA id isn't surfaced.
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

        for (entry in snap.stack.entries) {
            // Skip spell casts — those are projected as Cards in the stack zone via
            // [addSharedZoneCardsFromSnapshot]. The Ability projection path is for
            // triggered + activated SAs (Cascade trigger, Discover trigger, etc.).
            // Without this, late-snapshot timing where Forge has already removed the
            // spell from the stack zone but the entry lingers leaks an Ability with
            // grpId == sourceCardGrpId, masking the real triggered-ability projection.
            // Triggered abilities firing off a spell-on-stack (Cascade, source_zone=27)
            // need to project even when their source spell is still in the stack zone.
            if (entry.isSpell) continue

            val abilitySurrogate =
                if (entry.forgeAbilityId != 0) {
                    FrameIdResolver.triggerStackAbilityForgeId(entry.forgeAbilityId)
                } else {
                    FrameIdResolver.stackAbilityForgeId(entry.forgeCardId)
                }
            val abilityInstanceId = bridge.getOrAllocInstanceId(abilitySurrogate).value
            val grpId = entry.grpId.takeIf { it != 0 } ?: GameBridge.FALLBACK_GRPID
            // Degraded fallback: when [SnapshotCapture] couldn't resolve the source
            // card's Arena printing (synthetic test card, unrecognized token), reuse
            // the ability grpId rather than emit 0. This re-collapses grpId ==
            // objectSourceGrpId — the exact bug the field split was introduced to
            // fix — so warn loudly so a debug session knows to look here, not at
            // the resolver.
            val sourceCardGrpId =
                entry.sourceCardGrpId.takeIf { it != 0 } ?: run {
                    log.warn(
                        "stack ability sourceCardGrpId=0 for forgeCardId={}; " +
                            "falling back to ability grpId={} — collapsing the field split",
                        entry.forgeCardId,
                        grpId,
                    )
                    grpId
                }

            zoneBuilder.addObjectInstanceIds(abilityInstanceId)
            gameObjects.add(
                ObjectMapper.buildAbilityObject(
                    grpId = grpId,
                    sourceCardGrpId = sourceCardGrpId,
                    instanceId = abilityInstanceId,
                    ownerSeatId = entry.owner.value,
                    cardProto = bridge.cardProto,
                ),
            )
        }
        zones.add(zoneBuilder.build())
    }

    /**
     * Pick the chapter-specific ability grpId from a [CardData], independent of
     * the Forge stack entry. Extracted for unit-testability of both resolver
     * paths (populated [CardData.chapterAbilityGrpIds] vs positional fallback
     * via [CardData.abilityIds]).
     */
    internal fun chapterGrpIdFromCardData(
        cardData: CardData,
        chapterIdx: Int,
    ): Int? {
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
        seatId: SeatId,
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
            ZoneInfo
                .newBuilder()
                .setZoneId(handZoneId)
                .setType(ZoneType.Hand)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Private)
                .addViewers(seatId.value)
                .build(),
        )
        // Library — all cards (hand + library combined = full deck, pre-deal)
        val libBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(libZoneId)
                .setType(ZoneType.Library)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Hidden)
        for (fid in snap.zones[libZoneId]?.contents ?: emptyList()) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(fid).value)
        }
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            libBuilder.addObjectInstanceIds(bridge.getOrAllocInstanceId(fid).value)
        }
        zones.add(libBuilder.build())
        // Graveyard — empty
        zones.add(makeZone(gyZoneId, ZoneType.Graveyard, seatId.value, Visibility.Public))
        // Sideboard — empty, with viewer
        zones.add(
            ZoneInfo
                .newBuilder()
                .setZoneId(sbZoneId)
                .setType(ZoneType.Sideboard)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Private)
                .addViewers(seatId.value)
                .build(),
        )
    }

    // --- Helpers ---

    /** Build a basic ZoneInfo with no cards. */
    internal fun makeZone(
        zoneId: Int,
        type: ZoneType,
        ownerSeatId: Int,
        visibility: Visibility,
    ): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(visibility)
            .build()

    /** Private zone with viewers=[ownerSeatId] (hand, sideboard). */
    internal fun makePrivateZone(
        zoneId: Int,
        type: ZoneType,
        ownerSeatId: Int,
    ): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(Visibility.Private)
            .addViewers(ownerSeatId)
            .build()

    /** Returns the hand zone ID of the opponent, or 0 if viewingSeatId is 0 (no filtering). */
    internal fun opponentHandZone(viewingSeatId: Int): Int =
        when (viewingSeatId) {
            1 -> ZoneIds.P2_HAND
            2 -> ZoneIds.P1_HAND
            else -> 0
        }
}
