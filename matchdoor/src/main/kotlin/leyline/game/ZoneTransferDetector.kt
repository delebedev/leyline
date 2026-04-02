package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.findCard
import leyline.game.mapper.ObjectMapper
import leyline.game.mapper.ZoneIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

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
    val grpId: Int,
    val ownerSeatId: Int,
    /** InstanceId of the ability/spell that caused this transfer (for affectorId). */
    val affectorId: Int = 0,
    /** Color bitmasks for land color production (1=W, 2=U, 4=B, 8=R, 16=G). */
    val colorBitmasks: List<Int> = emptyList(),
    /** Resolved mana payments for CastSpell (one per land tapped). */
    val manaPayments: List<ManaPaymentRecord> = emptyList(),
    /** True if this transfer is an adventure spell cast (UserActionTaken actionType=16). */
    val isAdventureCast: Boolean = false,
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
    /** InstanceIds to retire to Limbo (caller applies via [ZoneTracking.retireToLimbo]). */
    val retiredIds: List<Int>,
    /** (instanceId, zoneId) pairs to record (caller applies via [ZoneTracking.recordZone]). */
    val zoneRecordings: List<Pair<Int, Int>>,
)

/**
 * Stage 1 of the annotation pipeline: detect zone transfers and realloc instanceIds.
 *
 * Extracted from [AnnotationPipeline] for independent maintainability.
 * Pure functions — no shared mutable state.
 */
object ZoneTransferDetector {
    private val log = LoggerFactory.getLogger(ZoneTransferDetector::class.java)

    /** Offset for mana ability instance IDs (separate from stack abilities at 100_000). */
    private const val MANA_ABILITY_ID_OFFSET = 200_000

    /**
     * Detect zone transfers and realloc instanceIds.
     *
     * Returns a [TransferResult] with patched copies of objects/zones.
     * Does not mutate [gameObjects] or [zones]. Calls [IdMapping.reallocInstanceId]
     * for ID allocation but defers tracking side effects (retireToLimbo, recordZone)
     * to the caller via the result.
     *
     * Delegates to the pure overload, adapting [GameBridge] calls to function parameters.
     */
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        bridge: GameBridge,
        events: List<GameEvent>,
    ): TransferResult = detectZoneTransfers(
        gameObjects = gameObjects,
        zones = zones,
        events = events,
        previousZones = bridge.diff.allZones(),
        forgeIdLookup = { iid -> bridge.getForgeCardId(iid) },
        idAllocator = { fid -> bridge.reallocInstanceId(fid) },
        idLookup = { fid -> bridge.getOrAllocInstanceId(fid) },
        manaAbilityGrpIdResolver = { fid ->
            val card = bridge.getGame()?.let { findCard(it, fid) }
            if (card != null) {
                val subtypes = card.type.subtypes.map { it.lowercase() }
                AbilityIdDeriver.BASIC_LAND_ABILITIES
                    .firstOrNull { it.first in subtypes }?.second ?: 0
            } else {
                0
            }
        },
    )

    /**
     * Detect zone transfers — pure overload.
     * Takes function parameters instead of [GameBridge] for independent testability.
     *
     * Returns a [TransferResult] with patched copies of objects/zones.
     * Does not mutate [gameObjects] or [zones]. Uses [idAllocator]
     * for ID allocation but defers tracking side effects (retireToLimbo, recordZone)
     * to the caller via the result.
     */
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> Int = { 0 },
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
                val category = if (forgeCardId != null && events.isNotEmpty()) {
                    AnnotationBuilder.categoryFromEvents(forgeCardId, events)
                        ?: inferCategory(obj, prevZone, obj.zoneId)
                } else {
                    inferCategory(obj, prevZone, obj.zoneId)
                }
                // Allocate new instanceId for zone transfer (real server does this).
                // Exception: Resolve (Stack→Battlefield) keeps the same instanceId.
                val realloc = if (!category.keepsSameInstanceId && forgeCardId != null) {
                    idAllocator(forgeCardId)
                } else {
                    InstanceIdRegistry.IdReallocation(InstanceId(obj.instanceId), InstanceId(obj.instanceId))
                }
                val origId = realloc.old.value
                val newId = realloc.new.value
                log.debug("zone transfer: iid {} → {} category={}", origId, newId, category)
                // Patch gameObject and zone with new instanceId
                if (newId != origId) {
                    patchedObjects[i] = obj.toBuilder().setInstanceId(newId).build()
                    patchZoneInstanceId(patchedZones, obj.zoneId, origId, newId)
                    retiredIds.add(origId)
                    appendToZone(patchedZones, ZoneIds.LIMBO, origId)
                }
                // Resolve affectorId: the ability instance that caused this transfer.
                // For surveil (and future mechanics), the source card's ability on the
                // stack has instanceId = getOrAlloc(sourceCardId + STACK_ABILITY_ID_OFFSET).
                val affectorId = if (forgeCardId != null && events.isNotEmpty()) {
                    val sourceCardId = AnnotationBuilder.affectorSourceFromEvents(forgeCardId, events)
                    if (sourceCardId != null) {
                        idLookup(ForgeCardId(sourceCardId.value + ObjectMapper.STACK_ABILITY_ID_OFFSET)).value
                    } else {
                        0
                    }
                } else {
                    0
                }

                // Extract color bitmasks from LandPlayed event for ColorProduction annotation.
                val colorBitmasks = if (category == TransferCategory.PlayLand && forgeCardId != null) {
                    events.filterIsInstance<GameEvent.LandPlayed>()
                        .firstOrNull { it.cardId == forgeCardId }
                        ?.colorBitmasks ?: emptyList()
                } else {
                    emptyList()
                }

                // Extract mana payment info + adventure flag from SpellCast events.
                val spellCastEvent = if (category == TransferCategory.CastSpell && forgeCardId != null) {
                    events.filterIsInstance<GameEvent.SpellCast>()
                        .firstOrNull { it.cardId == forgeCardId }
                } else {
                    null
                }
                val manaPayments = spellCastEvent?.manaPayments?.map { mp ->
                    val landIid = idLookup(mp.sourceCardId).value
                    val manaAbilityIid = idLookup(ForgeCardId(mp.sourceCardId.value + MANA_ABILITY_ID_OFFSET)).value
                    val abilityGrpId = manaAbilityGrpIdResolver(mp.sourceCardId)
                    ManaPaymentRecord(
                        landInstanceId = landIid,
                        manaAbilityInstanceId = manaAbilityIid,
                        color = mp.color,
                        abilityGrpId = abilityGrpId,
                        spellInstanceId = newId,
                    )
                } ?: emptyList()
                val isAdventureCast = spellCastEvent?.isAdventure == true

                transfers.add(
                    AppliedTransfer(
                        origId, newId, category, prevZone, obj.zoneId, obj.grpId,
                        obj.ownerSeatId, affectorId, colorBitmasks, manaPayments, isAdventureCast,
                    ),
                )
                zoneRecordings.add(newId to obj.zoneId)
            } else {
                zoneRecordings.add(obj.instanceId to obj.zoneId)
            }
        }

        // Post-pass: detect token sacrifices invisible to the main loop.
        detectDisappearedSacrifices(
            events, previousZones, patchedObjects, patchedZones,
            transfers, retiredIds, zoneRecordings,
            forgeIdLookup, idAllocator, idLookup, manaAbilityGrpIdResolver,
        )

        // Also record zones for instanceIds that appear only in zone objectInstanceIds
        // but not in gameObjects (e.g. library cards — hidden, no GameObjectInfo).
        // This enables zone-transfer detection when they later move to a visible zone.
        val gameObjectIds = patchedObjects.map { it.instanceId }.toSet()
        for (zone in patchedZones) {
            for (iid in zone.objectInstanceIdsList) {
                if (iid !in gameObjectIds) {
                    zoneRecordings.add(iid to zone.zoneId)
                }
            }
        }

        return TransferResult(transfers, patchedObjects, patchedZones, retiredIds, zoneRecordings)
    }

    /** Infer category for a zone transfer annotation from zone IDs. */
    @Suppress("CyclomaticComplexMethod", "UnusedParameter")
    fun inferCategory(obj: GameObjectInfo, srcZone: Int, destZone: Int): TransferCategory =
        when {
            srcZone == ZoneIds.P1_HAND || srcZone == ZoneIds.P2_HAND -> when (destZone) {
                ZoneIds.STACK -> TransferCategory.CastSpell
                ZoneIds.BATTLEFIELD -> TransferCategory.PlayLand
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZoneIds.STACK && destZone == ZoneIds.BATTLEFIELD -> TransferCategory.Resolve
            srcZone == ZoneIds.BATTLEFIELD -> when (destZone) {
                ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD -> TransferCategory.Destroy
                ZoneIds.EXILE -> TransferCategory.Exile
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZoneIds.P1_LIBRARY || srcZone == ZoneIds.P2_LIBRARY -> when (destZone) {
                ZoneIds.BATTLEFIELD -> TransferCategory.Search
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZoneIds.P1_GRAVEYARD || srcZone == ZoneIds.P2_GRAVEYARD -> when (destZone) {
                ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD -> TransferCategory.Return
                ZoneIds.EXILE -> TransferCategory.Exile
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZoneIds.EXILE -> when (destZone) {
                ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD -> TransferCategory.Return
                ZoneIds.STACK -> TransferCategory.CastSpell
                else -> TransferCategory.ZoneTransfer
            }
            else -> TransferCategory.ZoneTransfer
        }

    // --- private helpers ---

    /**
     * Detect token sacrifices invisible to the main transfer loop.
     *
     * Tokens sacrificed for mana (Treasure) are cleaned up by SBAs before the state
     * snapshot, making them invisible to zone-change detection. We find them by comparing
     * previousZones (battlefield) against current gameObjects. Also handles the case where
     * the token is still present (SBAs haven't run yet) but a CardSacrificed event fired.
     */
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
        manaAbilityGrpIdResolver: (ForgeCardId) -> Int,
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
            val realloc = idAllocator(forgeCardId)
            val origId = realloc.old.value
            val newId = realloc.new.value
            val ownerSeat = sacrificeEv.seatId
            val destZone = if (ownerSeat.value == 1) ZoneIds.P1_GRAVEYARD else ZoneIds.P2_GRAVEYARD

            // If still in gameObjects, strip it so the client sees it leave.
            val resolvedGrpId = if (stillOnBattlefield) {
                val idx = patchedObjects.indexOfFirst { it.instanceId == instanceId }
                val grp = if (idx >= 0) {
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

            val manaPayments = buildManaSacrificePayments(
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

            if (newId != origId) {
                retiredIds.add(origId)
                appendToZone(patchedZones, ZoneIds.LIMBO, origId)
            }

            transfers.add(
                AppliedTransfer(
                    origId,
                    newId,
                    TransferCategory.Sacrifice,
                    ZoneIds.BATTLEFIELD,
                    destZone,
                    resolvedGrpId,
                    ownerSeat.value,
                    manaPayments = manaPayments,
                ),
            )
            zoneRecordings.add(newId to destZone)
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
        manaAbilityGrpIdResolver: (ForgeCardId) -> Int,
    ): List<ManaPaymentRecord> {
        if (manaAbilityEvents.none { it.cardId == forgeCardId }) return emptyList()
        val castEv = spellCastEvents.firstOrNull { sc ->
            sc.manaPayments.any { it.sourceCardId == forgeCardId }
        } ?: return emptyList()
        val mp = castEv.manaPayments.first { it.sourceCardId == forgeCardId }
        return listOf(
            ManaPaymentRecord(
                landInstanceId = origId,
                manaAbilityInstanceId = idLookup(ForgeCardId(forgeCardId.value + MANA_ABILITY_ID_OFFSET)).value,
                color = mp.color,
                abilityGrpId = manaAbilityGrpIdResolver(forgeCardId),
                spellInstanceId = idLookup(castEv.cardId).value,
            ),
        )
    }

    /** Replace oldId with newId in a zone's objectInstanceIds list (after instanceId realloc). */
    private fun patchZoneInstanceId(zones: MutableList<ZoneInfo>, zoneId: Int, oldId: Int, newId: Int) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.toMutableList()
        val idIdx = ids.indexOf(oldId)
        if (idIdx >= 0) {
            ids[idIdx] = newId
            zones[idx] = zone.toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(ids)
                .build()
        }
    }

    /** Append an instanceId to a zone's objectInstanceIds list. */
    private fun appendToZone(zones: MutableList<ZoneInfo>, zoneId: Int, instanceId: Int) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        zones[idx] = zones[idx].toBuilder().addObjectInstanceIds(instanceId).build()
    }

    /** Remove an instanceId from a zone's objectInstanceIds list (no-op if not found). */
    private fun removeFromZone(zones: MutableList<ZoneInfo>, zoneId: Int, instanceId: Int) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.filter { it != instanceId }
        zones[idx] = zone.toBuilder()
            .clearObjectInstanceIds()
            .addAllObjectInstanceIds(ids)
            .build()
    }
}
