package leyline.game.annotations

import leyline.bridge.findCard
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.BasicLandAbilities
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.Foretell
import leyline.game.state.GameBridge
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.ZoneHandoff
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator

/** Pre-resolved mana payment: all IDs are client instanceIds, ready for annotation building. */
data class ManaPaymentRecord(
    val landInstanceId: Int,
    val manaAbilityInstanceId: Int,
    val color: Int,
    val abilityGrpId: Int,
    /** InstanceId of the spell/ability this mana pays for (ManaPaid.affectedIds). */
    val spellInstanceId: Int = 0,
)

data class AppliedTransfer(
    val origId: Int,
    val newId: Int,
    val category: TransferCategory,
    val srcZoneId: Int,
    val destZoneId: Int,
    val forgeCardId: ForgeCardId? = null,
    val grpId: Int,
    val ownerSeatId: Int,
    /** InstanceId of the ability/spell that caused this transfer (for affectorId). */
    val affectorId: Int = 0,
    /** client ManaColor ordinals for land color production (W=1, U=2, B=3, R=4, G=5). */
    val colorOrdinals: List<Int> = emptyList(),
    /** Resolved mana payments for CastSpell (one per land tapped). */
    val manaPayments: List<ManaPaymentRecord> = emptyList(),
    /** True if this transfer is an adventure spell cast (UserActionTaken actionType=16). */
    val isAdventureCast: Boolean = false,
    /** Non-zero when this CastSpell transfer used an alternate cost (Madness, Flashback,
     *  Warp, Cycling, Impending). Carries the client ability grpId for the alt-cost. */
    val altCostAbilityGrpId: Int = 0,
    /** Non-zero when the cast paid Kicker. Carries the per-card Kicker ability grpId. */
    val kickerAbilityGrpId: Int = 0,
    /** Non-zero when the cast chose an X value. Drives CastingTimeOption type=ChooseX. */
    val chosenX: Int = 0,
)

/** A triggered ability that just appeared on the stack (no previousZone entry). */
data class StackAbilityAppearance(
    val abilityInstanceId: Int,
    val sourceCardInstanceId: Int,
    val sourceZoneId: Int,
    val grpId: Int,
)

/** A triggered ability that was on the stack and is now gone (resolved or fizzled). */
data class StackAbilityDisappearance(
    val abilityInstanceId: Int,
    val sourceCardInstanceId: Int,
    val grpId: Int,
    val hasFizzled: Boolean,
)

/**
 * Result of zone-transfer detection.
 *
 * Contains patched copies of gameObjects/zones (with reallocated instanceIds
 * and Limbo entries) plus deferred side effects the caller must apply.
 */
data class TransferResult(
    /** Detected zone transfers for annotation building. */
    val transfers: List<AppliedTransfer>,
    /** GameObjects with instanceIds patched for zone transfers. */
    val patchedObjects: List<GameObjectInfo>,
    /** Zones with instanceIds patched + Limbo entries appended. */
    val patchedZones: List<ZoneInfo>,
    /** InstanceIds to retire to Limbo (caller applies via [leyline.game.state.ZoneTracking.retireToLimbo]). */
    val retiredIds: List<Int>,
    /** (instanceId, zoneId) pairs to record (caller applies via [leyline.game.state.ZoneTracking.recordZone]). */
    val zoneRecordings: List<Pair<Int, Int>>,
    /** Triggered abilities that just appeared on the stack. */
    val stackAbilityAppearances: List<StackAbilityAppearance> = emptyList(),
    /** Triggered abilities that left the stack (resolved/fizzled). */
    val stackAbilityDisappearances: List<StackAbilityDisappearance> = emptyList(),
    /**
     * Planned id reallocations for zone-transferred cards. Committed by
     * [GameBridge.applyMutations] after [leyline.game.mapping.StateMapper.buildDiff] returns.
     * Empty when no zone transfers occurred.
     */
    val idReallocations: List<InstanceIdRegistry.IdReallocation> = emptyList(),
)

/**
 * Stage 1 of the annotation pipeline: detect zone transfers and realloc instanceIds.
 *
 * Pure functions — no shared mutable state.
 */
@Suppress("LargeClass")
object ZoneTransferDetector {
    private val log = LoggerFactory.getLogger(ZoneTransferDetector::class.java)

    /**
     * Detect zone transfers and plan instanceId reallocations.
     *
     * Returns a [TransferResult] with patched copies of objects/zones. Does not
     * mutate [gameObjects] or [zones]. Id reallocations, limbo retires, and zone
     * recordings are returned as data; the caller commits via
     * [GameBridge.applyMutations].
     *
     * Delegates to the pure overload, adapting [GameBridge] calls to function
     * parameters.
     */
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        bridge: GameBridge,
        events: List<GameEvent>,
    ): TransferResult {
        val plannedReallocs = mutableListOf<InstanceIdRegistry.IdReallocation>()

        // Compute plans without mutating forward/reverse maps. Reserve a counter
        // slot per plan so monotonic getOrAlloc calls later in the same buildDiff
        // cannot collide. A forward/reverse overlay resolves same-pass queries for
        // freshly-planned fids. Caller commits the plans via bridge.applyMutations
        // (applyRealloc per plan).
        val forwardOverlay = mutableMapOf<ForgeCardId, InstanceId>()
        val reverseOverlay = mutableMapOf<InstanceId, ForgeCardId>()
        val idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation = { fid ->
            val oldId = forwardOverlay[fid] ?: bridge.ids.peek(fid)
            val newId = bridge.ids.reserveNextInstanceId()
            forwardOverlay[fid] = newId
            reverseOverlay[newId] = fid
            val plan = InstanceIdRegistry.IdReallocation(oldId ?: newId, newId)
            plannedReallocs.add(plan)
            plan
        }
        val forgeIdLookup: (InstanceId) -> ForgeCardId? = { iid ->
            reverseOverlay[iid] ?: bridge.getForgeCardId(iid)
        }
        val idLookup: (ForgeCardId) -> InstanceId = { fid ->
            forwardOverlay[fid] ?: bridge.getOrAllocInstanceId(fid)
        }

        val result =
            detectZoneTransfers(
                gameObjects = gameObjects,
                zones = zones,
                events = events,
                previousZones = bridge.diff.allZones(),
                forgeIdLookup = forgeIdLookup,
                idAllocator = idAllocator,
                idLookup = idLookup,
                manaAbilityGrpIdResolver = { fid ->
                    val card = bridge.getGame()?.let { findCard(it, fid) }
                    val abilityGrpId =
                        if (card != null) {
                            val subtypes = card.type.subtypes.map { it.lowercase() }
                            BasicLandAbilities.BY_SUBTYPE
                                .firstOrNull { it.first in subtypes }
                                ?.second ?: 0
                        } else {
                            0
                        }
                    GrpId(abilityGrpId)
                },
                grpIdResolver = { fid ->
                    val card = bridge.getGame()?.let { findCard(it, fid) }
                    GrpId(if (card != null) bridge.cardRepository.findGrpIdByName(card.name) ?: 0 else 0)
                },
                isForetoldLookup = { fid ->
                    bridge.getGame()?.let { findCard(it, fid) }?.let { Foretell.isForetold(it) } ?: false
                },
                forgeCardKnown = { fid ->
                    bridge.getGame()?.let { findCard(it, fid) } != null
                },
            )
        return result.copy(idReallocations = plannedReallocs.toList())
    }

    /**
     * Detect zone transfers — pure overload.
     * Takes function parameters instead of [GameBridge] for independent testability.
     *
     * Returns a [TransferResult] with patched copies of objects/zones.
     * Does not mutate [gameObjects] or [zones]. Uses [idAllocator]
     * for ID allocation but defers tracking side effects (retireToLimbo, recordZone)
     * to the caller via the result.
     */
    @Suppress(
        "LongMethod",
        // Detection branches per Forge zone-transfer category (cast, resolve,
        // exile, foretell, designation-related, mana-pay, etc.). Each new
        // category adds an arm.
        "CyclomaticComplexMethod",
        // Inherited from the upstream caller's parameter list — refactor would
        // also touch StateMapper threading.
        "LongParameterList",
    )
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId = { GrpId(0) },
        /** Resolve grpId for a source card's ForgeCardId (for stack ability resolution annotations). */
        grpIdResolver: (ForgeCardId) -> GrpId = { GrpId(0) },
        /** True when [forgeCardId] is currently foretold (in Exile with Card.foretold==true).
         *  Used to override Hand→Exile category from `Exile` to `Foretell` for the
         *  foretell-action transfer (Forge fires no dedicated GameEvent we can dispatch on
         *  — `GameEventCardForetold` carries only the activating player). */
        isForetoldLookup: (ForgeCardId) -> Boolean = { false },
        /** True when a Forge card with this id exists. Used by the stack-ability surrogate
         *  inverse-mapping fallback to reject SA ids that happen to numerically collide with
         *  unrelated card ids when no in-window event disambiguates. Defaults to `true` for
         *  the legacy callers that don't know the difference. */
        forgeCardKnown: (ForgeCardId) -> Boolean = { true },
    ): TransferResult {
        val patchedObjects = gameObjects.toMutableList()
        val patchedZones = zones.toMutableList()
        val transfers = mutableListOf<AppliedTransfer>()
        val retiredIds = mutableListOf<Int>()
        val zoneRecordings = mutableListOf<Pair<Int, Int>>()

        for (i in patchedObjects.indices) {
            val obj = patchedObjects[i]
            val prevZone = previousZones[obj.instanceId]
            if (prevZone != null && prevZone != obj.zoneId) {
                val forgeCardId = forgeIdLookup(InstanceId(obj.instanceId))
                val baseCategory =
                    if (forgeCardId != null && events.isNotEmpty()) {
                        TransferCategoryResolver.categoryFromEvents(forgeCardId, events)
                            ?: inferCategory(obj, prevZone, obj.zoneId)
                    } else {
                        inferCategory(obj, prevZone, obj.zoneId)
                    }
                // Foretell override: a Hand→Exile transfer where the destination card
                // is foretold (Card.foretold==true && Card.isInZone(Exile)) is the
                // foretell action, not a generic Exile. Forge fires no card-specific
                // event for this — we detect it via the post-transfer card state.
                val isHandToExile =
                    (prevZone == ZoneIds.P1_HAND || prevZone == ZoneIds.P2_HAND) &&
                        obj.zoneId == ZoneIds.EXILE
                val category =
                    if (isHandToExile && forgeCardId != null && isForetoldLookup(forgeCardId)) {
                        TransferCategory.Foretell
                    } else {
                        baseCategory
                    }
                // Allocate new instanceId for zone transfer (protocol requires this).
                // Exception: Resolve (Stack→Battlefield) keeps the same instanceId.
                val handoff =
                    if (!category.keepsSameInstanceId && forgeCardId != null) {
                        ZoneHandoff.fromRealloc(idAllocator(forgeCardId), obj.zoneId)
                    } else {
                        ZoneHandoff.keepingSameInstanceId(InstanceId(obj.instanceId), obj.zoneId)
                    }
                val origId = handoff.realloc.old.value
                val newId = handoff.realloc.new.value
                log.debug("zone transfer: iid {} → {} category={}", origId, newId, category)
                applyHandoffToPatchSet(handoff, patchedObjects, i, patchedZones, obj.zoneId, retiredIds)
                // Resolve affectorId: the ability instance that caused this transfer.
                // For surveil (and future mechanics), the source card's ability on the
                // stack has instanceId allocated against the SA-id-keyed surrogate
                // — see [FrameIdResolver.triggerStackAbilityForgeId]. Falls back to
                // source-card-keyed when no in-window SpellCast carries the SA id.
                val affectorId =
                    if (forgeCardId != null && events.isNotEmpty()) {
                        val sourceCardId = TransferCategoryResolver.affectorSourceFromEvents(forgeCardId, events)
                        if (sourceCardId != null) {
                            val abilityForgeId =
                                events
                                    .filterIsInstance<GameEvent.SpellCast>()
                                    .firstOrNull { it.cardId == sourceCardId && it.abilityForgeId != 0 }
                                    ?.abilityForgeId
                            val surrogate =
                                if (abilityForgeId != null) {
                                    FrameIdResolver.triggerStackAbilityForgeId(abilityForgeId)
                                } else {
                                    FrameIdResolver.stackAbilityForgeId(sourceCardId)
                                }
                            idLookup(surrogate).value
                        } else {
                            0
                        }
                    } else {
                        0
                    }

                // Extract color ordinals from LandPlayed event for ColorProduction annotation.
                val colorOrdinals =
                    if (category == TransferCategory.PlayLand && forgeCardId != null) {
                        events
                            .filterIsInstance<GameEvent.LandPlayed>()
                            .firstOrNull { it.cardId == forgeCardId }
                            ?.colorOrdinals ?: emptyList()
                    } else {
                        emptyList()
                    }

                // Extract mana payment info + adventure flag + alt-cost info from
                // SpellCast events.
                val spellCastEvent =
                    if (category == TransferCategory.CastSpell && forgeCardId != null) {
                        events
                            .filterIsInstance<GameEvent.SpellCast>()
                            .firstOrNull { it.cardId == forgeCardId }
                    } else {
                        null
                    }
                val manaPayments =
                    spellCastEvent?.manaPayments?.map { mp ->
                        val landIid = idLookup(mp.sourceCardId).value
                        val manaAbilityIid = idLookup(FrameIdResolver.manaAbilityForgeId(mp.sourceCardId)).value
                        val abilityGrpId = manaAbilityGrpIdResolver(mp.sourceCardId).value
                        ManaPaymentRecord(
                            landInstanceId = landIid,
                            manaAbilityInstanceId = manaAbilityIid,
                            color = mp.color,
                            abilityGrpId = abilityGrpId,
                            spellInstanceId = newId,
                        )
                    } ?: emptyList()
                val isAdventureCast = spellCastEvent?.isAdventure == true
                val altCostAbilityGrpId = spellCastEvent?.altCostAbilityGrpId ?: 0
                val kickerAbilityGrpId = spellCastEvent?.kickerAbilityGrpId ?: 0
                val chosenX = spellCastEvent?.chosenX ?: 0

                transfers.add(
                    AppliedTransfer(
                        origId = origId,
                        newId = newId,
                        category = category,
                        srcZoneId = prevZone,
                        destZoneId = obj.zoneId,
                        forgeCardId = forgeCardId,
                        grpId = obj.grpId,
                        ownerSeatId = obj.ownerSeatId,
                        affectorId = affectorId,
                        colorOrdinals = colorOrdinals,
                        manaPayments = manaPayments,
                        isAdventureCast = isAdventureCast,
                        altCostAbilityGrpId = altCostAbilityGrpId,
                        kickerAbilityGrpId = kickerAbilityGrpId,
                        chosenX = chosenX,
                    ),
                )
                zoneRecordings.add(newId to obj.zoneId)
            } else {
                zoneRecordings.add(obj.instanceId to obj.zoneId)
            }
        }

        // Post-pass: detect token sacrifices invisible to the main loop.
        detectDisappearedSacrifices(
            events,
            previousZones,
            patchedObjects,
            patchedZones,
            transfers,
            retiredIds,
            zoneRecordings,
            forgeIdLookup,
            idAllocator,
            idLookup,
            manaAbilityGrpIdResolver,
        )

        // Post-pass: detect exile-return transforms (saga final chapter,
        // Fable of the Mirror-Breaker). Forge fires paired ChangeZone events
        // (BF→Exile, Exile→BF) during an atomic resolve. Net snapshot shows
        // the card still on BF so the main diff loop skips it, but the client
        // protocol requires the two zone-hop annotations.
        detectExileReturnRoundTrips(
            events,
            transfers,
            patchedObjects,
            patchedZones,
            retiredIds,
            idAllocator,
            idLookup,
        )

        val gameObjectIds = patchedObjects.map { it.instanceId }.toSet()

        // Post-pass: detect transfers into hidden zones. Library / hidden-hand
        // cards can appear only in ZoneInfo.objectInstanceIds, with no
        // GameObjectInfo for the main loop to inspect.
        detectZoneOnlyTransfers(
            events,
            previousZones,
            gameObjectIds,
            patchedZones,
            transfers,
            retiredIds,
            zoneRecordings,
            forgeIdLookup,
            idAllocator,
            grpIdResolver,
        )

        // Post-pass: detect triggered ability lifecycle on the stack.
        val mainLoopIds = transfers.map { it.origId }.toSet()
        val appearances =
            detectStackAbilityAppearances(
                patchedObjects,
                previousZones,
                mainLoopIds,
                forgeIdLookup,
                idLookup,
                events,
                forgeCardKnown,
            )
        val (disappearances, disappearedRetiredIds) =
            detectStackAbilityDisappearances(
                events,
                previousZones,
                gameObjectIds,
                mainLoopIds,
                forgeIdLookup,
                idLookup,
                grpIdResolver,
                forgeCardKnown,
            )
        // Retire disappeared ability instanceIds to Limbo so annotation
        // references (affectedIds) remain resolvable by the validating sink.
        for (id in disappearedRetiredIds) {
            retiredIds.add(id)
            appendToZone(patchedZones, ZoneIds.LIMBO, id)
        }

        // Record zones for instanceIds that appear only in zone objectInstanceIds
        // but not in gameObjects (e.g. library cards — hidden, no GameObjectInfo).
        for (zone in patchedZones) {
            for (iid in zone.objectInstanceIdsList) {
                val zonePair = iid to zone.zoneId
                if (iid !in gameObjectIds && zonePair !in zoneRecordings) {
                    zoneRecordings.add(zonePair)
                }
            }
        }

        return TransferResult(
            transfers,
            patchedObjects,
            patchedZones,
            retiredIds,
            zoneRecordings,
            stackAbilityAppearances = appearances,
            stackAbilityDisappearances = disappearances,
        )
    }

    /** Infer category for a zone transfer annotation from zone IDs. */
    @Suppress("CyclomaticComplexMethod", "UnusedParameter")
    fun inferCategory(
        obj: GameObjectInfo,
        srcZone: Int,
        destZone: Int,
    ): TransferCategory =
        when {
            srcZone == ZoneIds.P1_HAND || srcZone == ZoneIds.P2_HAND ->
                when (destZone) {
                    ZoneIds.STACK -> TransferCategory.CastSpell
                    ZoneIds.BATTLEFIELD -> TransferCategory.PlayLand
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.STACK && destZone == ZoneIds.BATTLEFIELD -> TransferCategory.Resolve
            srcZone == ZoneIds.BATTLEFIELD ->
                when (destZone) {
                    ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD -> TransferCategory.Destroy
                    ZoneIds.EXILE -> TransferCategory.Exile
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.P1_LIBRARY || srcZone == ZoneIds.P2_LIBRARY ->
                when (destZone) {
                    ZoneIds.BATTLEFIELD -> TransferCategory.Search
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.P1_GRAVEYARD || srcZone == ZoneIds.P2_GRAVEYARD ->
                when (destZone) {
                    ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD -> TransferCategory.Return
                    ZoneIds.EXILE -> TransferCategory.Exile
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.EXILE ->
                when (destZone) {
                    ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD -> TransferCategory.Return
                    ZoneIds.STACK -> TransferCategory.CastSpell
                    else -> TransferCategory.ZoneTransfer
                }
            else -> TransferCategory.ZoneTransfer
        }

    @Suppress("LongParameterList")
    private fun detectZoneOnlyTransfers(
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        gameObjectIds: Set<Int>,
        patchedZones: MutableList<ZoneInfo>,
        transfers: MutableList<AppliedTransfer>,
        retiredIds: MutableList<Int>,
        zoneRecordings: MutableList<Pair<Int, Int>>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        grpIdResolver: (ForgeCardId) -> GrpId,
    ) {
        val currentZoneById =
            patchedZones
                .asSequence()
                .flatMap { zone -> zone.objectInstanceIdsList.asSequence().map { iid -> iid to zone.zoneId } }
                .toMap()

        for ((iid, destZone) in currentZoneById) {
            if (iid in gameObjectIds) continue
            if (destZone == ZoneIds.LIMBO) continue
            if (transfers.any { it.origId == iid || it.newId == iid }) continue
            val prevZone = previousZones[iid] ?: continue
            if (prevZone == destZone) continue

            val forgeCardId = forgeIdLookup(InstanceId(iid)) ?: continue
            val ownerSeatId = ownerSeatIdForZone(destZone) ?: ownerSeatIdForZone(prevZone) ?: 0
            if (isCollapsedOmenTransfer(events, forgeCardId, prevZone, destZone)) {
                // Compatibility fallback: Forge can collapse a local Omen cast
                // into a final hidden Hand->Library snapshot. Reconstruct the
                // client lifecycle well enough to avoid a stale hand object; a
                // full Omen implementation should emit from real stack frames.
                val castHandoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), ZoneIds.STACK)
                val resolveHandoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), destZone)
                val handId = castHandoff.realloc.old.value
                val stackId = castHandoff.realloc.new.value
                val libraryId = resolveHandoff.realloc.new.value
                val grpId = grpIdResolver(forgeCardId).value

                patchZoneInstanceId(patchedZones, destZone, iid, libraryId)
                retiredIds.add(handId)
                appendToZone(patchedZones, ZoneIds.LIMBO, handId)
                retiredIds.add(stackId)
                appendToZone(patchedZones, ZoneIds.LIMBO, stackId)
                transfers.add(
                    AppliedTransfer(
                        origId = handId,
                        newId = stackId,
                        category = TransferCategory.CastSpell,
                        srcZoneId = prevZone,
                        destZoneId = ZoneIds.STACK,
                        forgeCardId = forgeCardId,
                        grpId = grpId,
                        ownerSeatId = ownerSeatId,
                    ),
                )
                transfers.add(
                    AppliedTransfer(
                        origId = stackId,
                        newId = libraryId,
                        category = TransferCategory.Resolve,
                        srcZoneId = ZoneIds.STACK,
                        destZoneId = destZone,
                        forgeCardId = forgeCardId,
                        grpId = grpId,
                        ownerSeatId = ownerSeatId,
                    ),
                )
                zoneRecordings.add(libraryId to destZone)
                log.debug(
                    "zone-only Omen transfer: iid {} -> stack {} -> library {}",
                    handId,
                    stackId,
                    libraryId,
                )
                continue
            }
            val category =
                if (events.isNotEmpty()) {
                    TransferCategoryResolver.categoryFromEvents(forgeCardId, events)
                        ?: inferCategory(GameObjectInfo.getDefaultInstance(), prevZone, destZone)
                } else {
                    inferCategory(GameObjectInfo.getDefaultInstance(), prevZone, destZone)
                }
            val handoff =
                if (!category.keepsSameInstanceId) {
                    ZoneHandoff.fromRealloc(idAllocator(forgeCardId), destZone)
                } else {
                    ZoneHandoff.keepingSameInstanceId(InstanceId(iid), destZone)
                }
            val origId = handoff.realloc.old.value
            val newId = handoff.realloc.new.value

            applyHiddenHandoffToZoneSet(handoff, patchedZones, destZone, retiredIds)
            transfers.add(
                AppliedTransfer(
                    origId = origId,
                    newId = newId,
                    category = category,
                    srcZoneId = prevZone,
                    destZoneId = destZone,
                    forgeCardId = forgeCardId,
                    grpId = grpIdResolver(forgeCardId).value,
                    ownerSeatId = ownerSeatId,
                ),
            )
            zoneRecordings.add(newId to destZone)
            log.debug("zone-only transfer: iid {} -> {} category={}", origId, newId, category)
        }
    }

    private fun isCollapsedOmenTransfer(
        events: List<GameEvent>,
        forgeCardId: ForgeCardId,
        prevZone: Int,
        destZone: Int,
    ): Boolean {
        val handToLibrary =
            (prevZone == ZoneIds.P1_HAND || prevZone == ZoneIds.P2_HAND) &&
                (destZone == ZoneIds.P1_LIBRARY || destZone == ZoneIds.P2_LIBRARY)
        if (!handToLibrary) return false
        val omenCast =
            events.any {
                it is GameEvent.SpellCast &&
                    it.cardId == forgeCardId &&
                    it.isOmen &&
                    !it.isAbility &&
                    !it.isTrigger
            }
        val resolved =
            events.any {
                it is GameEvent.SpellResolved &&
                    it.cardId == forgeCardId &&
                    !it.hasFizzled
            }
        return omenCast && resolved
    }

    private fun ownerSeatIdForZone(zoneId: Int): Int? =
        when (zoneId) {
            ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, ZoneIds.P1_SIDEBOARD, ZoneIds.REVEALED_P1 -> 1
            ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, ZoneIds.P2_GRAVEYARD, ZoneIds.P2_SIDEBOARD, ZoneIds.REVEALED_P2 -> 2
            else -> null
        }

    /**
     * Recover the source card forge id for a stack-ability surrogate.
     *
     * The surrogate inverse `abilityForgeId.value - STACK_ABILITY_ID_OFFSET`
     * yields different things under the two surrogate schemes:
     *   - SA-id-keyed surrogate → the Forge SpellAbility id
     *   - source-card-keyed surrogate (legacy fallback) → the source card forge id
     *
     * Disambiguation: when an in-window `SpellCast` / `SpellResolved` event
     * with matching `abilityForgeId` is present, its `cardId` is the source
     * card. Otherwise the inverse value is only safe to treat as a source-card
     * forge id when [forgeCardKnown] confirms a card with that id exists; SA
     * ids and card ids share the same numeric range so an unguarded fallback
     * can resolve to the wrong card and silently mis-emit grpId / sourceIid.
     * Returns `null` when no event disambiguates and the inverse isn't a
     * known card — the caller drops the appearance/disappearance.
     */
    private fun resolveStackAbilitySourceCard(
        abilityForgeId: ForgeCardId,
        events: List<GameEvent>,
        eventFilter: (GameEvent) -> Boolean,
        forgeCardKnown: (ForgeCardId) -> Boolean,
    ): ForgeCardId? {
        if (!FrameIdResolver.isStackAbilityForgeId(abilityForgeId)) return null
        val inverseValue = FrameIdResolver.stackAbilitySourceForgeId(abilityForgeId).value
        for (ev in events) {
            if (!eventFilter(ev)) continue
            val saId = abilityForgeIdOf(ev) ?: continue
            if (saId != inverseValue) continue
            return cardIdOf(ev)
        }
        val candidate = ForgeCardId(inverseValue)
        return if (forgeCardKnown(candidate)) {
            candidate
        } else {
            log.debug(
                "stack ability surrogate {} could not disambiguate source card via events; inverse {} not a known card forge id",
                abilityForgeId.value,
                inverseValue,
            )
            null
        }
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun abilityForgeIdOf(ev: GameEvent): Int? =
        when (ev) {
            is GameEvent.SpellCast -> ev.abilityForgeId.takeIf { it != 0 }
            is GameEvent.SpellResolved -> ev.abilityForgeId.takeIf { it != 0 }
            else -> null
        }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun cardIdOf(ev: GameEvent): ForgeCardId? =
        when (ev) {
            is GameEvent.SpellCast -> ev.cardId
            is GameEvent.SpellResolved -> ev.cardId
            else -> null
        }

    // --- private helpers ---

    /**
     * Detect triggered abilities that just appeared on the stack.
     * These are [GameObjectType.Ability] objects in the stack zone with no [previousZones] entry.
     */
    @Suppress("LongParameterList")
    private fun detectStackAbilityAppearances(
        patchedObjects: List<GameObjectInfo>,
        previousZones: Map<Int, Int>,
        mainLoopIds: Set<Int>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idLookup: (ForgeCardId) -> InstanceId,
        events: List<GameEvent>,
        forgeCardKnown: (ForgeCardId) -> Boolean,
    ): List<StackAbilityAppearance> {
        val appearances = mutableListOf<StackAbilityAppearance>()
        for (obj in patchedObjects) {
            if (obj.type != GameObjectType.Ability) continue
            if (obj.zoneId != ZoneIds.STACK) continue
            if (obj.instanceId in mainLoopIds) continue
            if (previousZones.containsKey(obj.instanceId)) continue

            val abilityForgeId = forgeIdLookup(InstanceId(obj.instanceId)) ?: continue
            val sourceCardForgeId =
                resolveStackAbilitySourceCard(
                    abilityForgeId,
                    events,
                    eventFilter = { ev -> ev is GameEvent.SpellCast },
                    forgeCardKnown = forgeCardKnown,
                ) ?: continue
            val sourceCardIid = idLookup(sourceCardForgeId).value
            val sourceZoneId = if (sourceCardIid > 0) previousZones[sourceCardIid] ?: 0 else 0

            appearances.add(
                StackAbilityAppearance(
                    abilityInstanceId = obj.instanceId,
                    sourceCardInstanceId = sourceCardIid,
                    sourceZoneId = sourceZoneId,
                    grpId = obj.grpId,
                ),
            )
            log.debug("stack ability appeared: iid={} grpId={} source={}", obj.instanceId, obj.grpId, sourceCardIid)
        }
        return appearances
    }

    /**
     * Detect triggered abilities that left the stack (resolved or fizzled).
     * Finds instanceIds in [previousZones] that were on the stack but are absent from current objects.
     *
     * Returns (disappearances, retiredInstanceIds). Caller folds retired IDs into
     * [TransferResult] and appends them to Limbo — keeps this function pure.
     */
    @Suppress("LongParameterList")
    private fun detectStackAbilityDisappearances(
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        currentInstanceIds: Set<Int>,
        mainLoopIds: Set<Int>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idLookup: (ForgeCardId) -> InstanceId,
        grpIdResolver: (ForgeCardId) -> GrpId,
        forgeCardKnown: (ForgeCardId) -> Boolean,
    ): Pair<List<StackAbilityDisappearance>, List<Int>> {
        val resolvedEvents = events.filterIsInstance<GameEvent.SpellResolved>()
        val disappearances = mutableListOf<StackAbilityDisappearance>()
        val newRetiredIds = mutableListOf<Int>()

        for ((instanceId, zoneId) in previousZones) {
            if (zoneId != ZoneIds.STACK) continue
            if (instanceId in currentInstanceIds) continue
            if (instanceId in mainLoopIds) continue

            // Only match ability objects (forge ID in the stack-ability surrogate range).
            val abilityForgeId = forgeIdLookup(InstanceId(instanceId)) ?: continue
            if (!FrameIdResolver.isStackAbilityForgeId(abilityForgeId)) continue

            val sourceCardForgeId =
                resolveStackAbilitySourceCard(
                    abilityForgeId,
                    events,
                    eventFilter = { ev -> ev is GameEvent.SpellResolved },
                    forgeCardKnown = forgeCardKnown,
                ) ?: continue
            val sourceCardIid = idLookup(sourceCardForgeId).value
            val grpId = grpIdResolver(sourceCardForgeId).value

            // Correlate with SpellResolved event for fizzle detection.
            val resolvedEv = resolvedEvents.firstOrNull { it.cardId == sourceCardForgeId }
            val hasFizzled = resolvedEv?.hasFizzled == true

            newRetiredIds.add(instanceId)
            disappearances.add(
                StackAbilityDisappearance(
                    abilityInstanceId = instanceId,
                    sourceCardInstanceId = sourceCardIid,
                    grpId = grpId,
                    hasFizzled = hasFizzled,
                ),
            )
            log.debug("stack ability disappeared: iid={} grpId={} fizzled={}", instanceId, grpId, hasFizzled)
        }
        return disappearances to newRetiredIds
    }

    /**
     * Detect exile-return round-trips: Forge fired paired ChangeZone events
     * (BF→Exile, Exile→BF) for the same card within one resolve — typical of
     * saga final-chapter transforms (`DB$ ChangeZone | Origin$ Battlefield |
     * Destination$ Exile | SubAbility$ ChangeZone | ... | Origin$ Exile |
     * Destination$ Battlefield | Transformed$ True`).
     *
     * The main snapshot-diff loop misses these because the net state shows
     * the card still on Battlefield. Emit synthetic [TransferCategory.Exile]
     * and [TransferCategory.Return] transfers with two fresh allocations so
     * the client sees the expected ObjectIdChanged + ZoneTransfer pairs.
     *
     * Expected wire shape:
     * `ObjectIdChanged(A→B)` + `ZT(B, BF→Exile, "Exile")` +
     * `ObjectIdChanged(B→C)` + `ZT(C, Exile→BF, "Return")`.
     */
    @Suppress("LongParameterList")
    private fun detectExileReturnRoundTrips(
        events: List<GameEvent>,
        transfers: MutableList<AppliedTransfer>,
        patchedObjects: MutableList<GameObjectInfo>,
        patchedZones: MutableList<ZoneInfo>,
        retiredIds: MutableList<Int>,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
    ) {
        // Dedupe by ForgeCardId: if a card bounces exile→return multiple times in
        // one resolve (delayed-trigger + chapter interactions), we only synthesize
        // once — the later pairs would try to retire already-retired iids.
        val exiled =
            events
                .filterIsInstance<GameEvent.CardExiled>()
                .filter { it.fromBattlefield }
                .distinctBy { it.cardId }
        if (exiled.isEmpty()) return

        for (ev in exiled) {
            // Match a subsequent Exile→BF ZoneChanged for the same Forge card.
            val returned =
                events
                    .filterIsInstance<GameEvent.ZoneChanged>()
                    .any {
                        it.cardId == ev.cardId && it.from == Zone.Exile && it.to == Zone.Battlefield
                    }
            if (!returned) continue

            val currentIid = idLookup(ev.cardId).value
            val objIdx = patchedObjects.indexOfFirst { it.instanceId == currentIid }
            if (objIdx < 0) continue
            val currentObj = patchedObjects[objIdx]
            if (currentObj.zoneId != ZoneIds.BATTLEFIELD) continue

            // Skip if the main loop or another post-pass already accounted for this.
            if (transfers.any { it.origId == currentIid || it.newId == currentIid }) continue

            // Allocate two fresh instanceIds: one for the Exile step, one for Return.
            val exileAlloc = idAllocator(ev.cardId)
            val exileIid = exileAlloc.new.value
            val returnAlloc = idAllocator(ev.cardId)
            val returnIid = returnAlloc.new.value

            transfers.add(
                AppliedTransfer(
                    origId = currentIid,
                    newId = exileIid,
                    category = TransferCategory.Exile,
                    srcZoneId = ZoneIds.BATTLEFIELD,
                    destZoneId = ZoneIds.EXILE,
                    forgeCardId = ev.cardId,
                    grpId = currentObj.grpId,
                    ownerSeatId = currentObj.ownerSeatId,
                    affectorId = 0,
                ),
            )
            transfers.add(
                AppliedTransfer(
                    origId = exileIid,
                    newId = returnIid,
                    category = TransferCategory.Return,
                    srcZoneId = ZoneIds.EXILE,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    forgeCardId = ev.cardId,
                    grpId = currentObj.grpId,
                    ownerSeatId = currentObj.ownerSeatId,
                    affectorId = 0,
                ),
            )

            retiredIds.add(currentIid)
            retiredIds.add(exileIid)
            appendToZone(patchedZones, ZoneIds.LIMBO, currentIid)
            appendToZone(patchedZones, ZoneIds.LIMBO, exileIid)
            // Synthesize intermediate GameObjectInfos for the retired iids so
            // ZoneTransfer annotations referencing them resolve against a real
            // object (matches tribute-to-horobi.md gsId 145: both 288 and 318
            // persist in Limbo alongside 319 on BF for animation continuity).
            patchedObjects.add(
                currentObj
                    .toBuilder()
                    .setInstanceId(currentIid)
                    .setZoneId(ZoneIds.LIMBO)
                    .build(),
            )
            patchedObjects.add(
                currentObj
                    .toBuilder()
                    .setInstanceId(exileIid)
                    .setZoneId(ZoneIds.LIMBO)
                    .build(),
            )
            patchedObjects[objIdx] = currentObj.toBuilder().setInstanceId(returnIid).build()
            patchZoneInstanceId(patchedZones, ZoneIds.BATTLEFIELD, currentIid, returnIid)

            log.debug(
                "exile-return transform: forgeCardId={} currentIid={} exileIid={} returnIid={}",
                ev.cardId.value,
                currentIid,
                exileIid,
                returnIid,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun detectDisappearedSacrifices(
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        patchedObjects: MutableList<GameObjectInfo>,
        patchedZones: MutableList<ZoneInfo>,
        transfers: MutableList<AppliedTransfer>,
        retiredIds: MutableList<Int>,
        zoneRecordings: MutableList<Pair<Int, Int>>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId,
    ) {
        val currentInstanceIds = patchedObjects.map { it.instanceId }.toSet()
        val sacrificeEvents = events.filterIsInstance<GameEvent.CardSacrificed>()
        if (sacrificeEvents.isEmpty()) return
        val manaAbilityEvents = events.filterIsInstance<GameEvent.ManaAbilityActivated>()
        val spellCastEvents = events.filterIsInstance<GameEvent.SpellCast>()
        // Skip instanceIds already processed by the main transfer loop to avoid
        // double-processing regular (non-token) sacrifices that are still in gameObjects.
        val mainLoopOrigIds = transfers.map { it.origId }.toSet()

        for ((instanceId, zoneId) in previousZones) {
            if (zoneId != ZoneIds.BATTLEFIELD) continue
            if (instanceId in mainLoopOrigIds) continue
            val forgeCardId = forgeIdLookup(InstanceId(instanceId)) ?: continue
            val sacrificeEv = sacrificeEvents.firstOrNull { it.cardId == forgeCardId } ?: continue

            val stillOnBattlefield = instanceId in currentInstanceIds
            val ownerSeat = sacrificeEv.seatId
            val destZone = ZoneIds.graveyardOf(ownerSeat)
            val handoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), destZone)
            val origId = handoff.realloc.old.value
            val newId = handoff.realloc.new.value

            // If still in gameObjects, strip it so the client sees it leave.
            val resolvedGrpId =
                if (stillOnBattlefield) {
                    val idx = patchedObjects.indexOfFirst { it.instanceId == instanceId }
                    val grp =
                        if (idx >= 0) {
                            val g = patchedObjects[idx].grpId
                            patchedObjects.removeAt(idx)
                            g
                        } else {
                            0
                        }
                    removeFromZone(patchedZones, ZoneIds.BATTLEFIELD, instanceId)
                    appendToZone(patchedZones, destZone, newId)
                    grp
                } else {
                    0
                }

            val manaPayments =
                buildManaSacrificePayments(
                    forgeCardId,
                    origId,
                    manaAbilityEvents,
                    spellCastEvents,
                    idLookup,
                    manaAbilityGrpIdResolver,
                )

            // Remove this mana source from CastSpell transfers to avoid duplication.
            if (manaPayments.isNotEmpty()) {
                for (i in transfers.indices) {
                    val t = transfers[i]
                    if (t.category == TransferCategory.CastSpell && t.manaPayments.any { it.landInstanceId == origId }) {
                        transfers[i] = t.copy(manaPayments = t.manaPayments.filter { it.landInstanceId != origId })
                    }
                }
            }

            handoff.limboRetirement?.let { limbo ->
                retiredIds.add(limbo.value)
                appendToZone(patchedZones, ZoneIds.LIMBO, limbo.value)
            }

            transfers.add(
                AppliedTransfer(
                    origId = origId,
                    newId = newId,
                    category = TransferCategory.Sacrifice,
                    srcZoneId = ZoneIds.BATTLEFIELD,
                    destZoneId = destZone,
                    forgeCardId = forgeCardId,
                    grpId = resolvedGrpId,
                    ownerSeatId = ownerSeat.value,
                    manaPayments = manaPayments,
                ),
            )
            val (recIid, recZone) = handoff.zoneAssignment
            zoneRecordings.add(recIid.value to recZone)
            log.debug("disappeared token: iid {} → {} category=Sacrifice manaPayments={}", origId, newId, manaPayments.size)
        }
    }

    /** Build mana payment records for a sacrifice that activated a mana ability. */
    private fun buildManaSacrificePayments(
        forgeCardId: ForgeCardId,
        origId: Int,
        manaAbilityEvents: List<GameEvent.ManaAbilityActivated>,
        spellCastEvents: List<GameEvent.SpellCast>,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId,
    ): List<ManaPaymentRecord> {
        if (manaAbilityEvents.none { it.cardId == forgeCardId }) return emptyList()
        val castEv =
            spellCastEvents.firstOrNull { sc ->
                sc.manaPayments.any { it.sourceCardId == forgeCardId }
            } ?: return emptyList()
        val mp = castEv.manaPayments.first { it.sourceCardId == forgeCardId }
        return listOf(
            ManaPaymentRecord(
                landInstanceId = origId,
                manaAbilityInstanceId = idLookup(FrameIdResolver.manaAbilityForgeId(forgeCardId)).value,
                color = mp.color,
                abilityGrpId = manaAbilityGrpIdResolver(forgeCardId).value,
                spellInstanceId = idLookup(castEv.cardId).value,
            ),
        )
    }

    /**
     * Apply a [ZoneHandoff]'s structural mutations to the local patch-set:
     * rewrite the GameObject's instanceId, rewrite the source zone's
     * `objectInstanceIds` list, append the old iid to Limbo, and signal
     * retirement to the caller's [retiredIds] accumulator.
     *
     * No-op when [ZoneHandoff.limboRetirement] is null (Resolve / keep-same-iid).
     */
    private fun applyHandoffToPatchSet(
        handoff: ZoneHandoff,
        patchedObjects: MutableList<GameObjectInfo>,
        objectIndex: Int,
        patchedZones: MutableList<ZoneInfo>,
        sourceZoneId: Int,
        retiredIds: MutableList<Int>,
    ) {
        val limbo = handoff.limboRetirement ?: return
        val origId = limbo.value
        val newId = handoff.realloc.new.value
        val obj = patchedObjects[objectIndex]
        patchedObjects[objectIndex] = obj.toBuilder().setInstanceId(newId).build()
        patchZoneInstanceId(patchedZones, sourceZoneId, origId, newId)
        retiredIds.add(origId)
        appendToZone(patchedZones, ZoneIds.LIMBO, origId)
    }

    private fun applyHiddenHandoffToZoneSet(
        handoff: ZoneHandoff,
        patchedZones: MutableList<ZoneInfo>,
        destinationZoneId: Int,
        retiredIds: MutableList<Int>,
    ) {
        val limbo = handoff.limboRetirement ?: return
        val origId = limbo.value
        val newId = handoff.realloc.new.value
        patchZoneInstanceId(patchedZones, destinationZoneId, origId, newId)
        retiredIds.add(origId)
        appendToZone(patchedZones, ZoneIds.LIMBO, origId)
    }

    /** Replace oldId with newId in a zone's objectInstanceIds list (after instanceId realloc). */
    private fun patchZoneInstanceId(
        zones: MutableList<ZoneInfo>,
        zoneId: Int,
        oldId: Int,
        newId: Int,
    ) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.toMutableList()
        val idIdx = ids.indexOf(oldId)
        if (idIdx >= 0) {
            ids[idIdx] = newId
            zones[idx] =
                zone
                    .toBuilder()
                    .clearObjectInstanceIds()
                    .addAllObjectInstanceIds(ids)
                    .build()
        }
    }

    /** Append an instanceId to a zone's objectInstanceIds list. */
    private fun appendToZone(
        zones: MutableList<ZoneInfo>,
        zoneId: Int,
        instanceId: Int,
    ) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        zones[idx] = zones[idx].toBuilder().addObjectInstanceIds(instanceId).build()
    }

    /** Remove an instanceId from a zone's objectInstanceIds list (no-op if not found). */
    private fun removeFromZone(
        zones: MutableList<ZoneInfo>,
        zoneId: Int,
        instanceId: Int,
    ) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.filter { it != instanceId }
        zones[idx] =
            zone
                .toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(ids)
                .build()
    }
}
