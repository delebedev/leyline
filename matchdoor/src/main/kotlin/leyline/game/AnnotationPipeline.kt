package leyline.game

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.game.mapper.PlayerMapper
import leyline.game.mapper.ZoneIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Four-stage annotation pipeline for zone-transfer detection and annotation building.
 *
 * Stage 1: [detectZoneTransfers] → [TransferResult] (patched objects/zones + transfers)
 * Stage 2: [annotationsForTransfer] → List<[AnnotationInfo]> (pure, testable)
 * Stage 3: [combatAnnotations] → List<[AnnotationInfo]> (pure, testable)
 * Stage 4: [mechanicAnnotations] → [MechanicAnnotationResult] (Group B: counters, shuffle, scry, tokens + Group A+: attachments)
 *
 * Extracted from [StateMapper] for independent testability.
 */
object AnnotationPipeline {
    private val log = LoggerFactory.getLogger(AnnotationPipeline::class.java)

    // Zone ID constants needed by the pipeline
    private const val ZONE_STACK = ZoneIds.STACK
    private const val ZONE_BATTLEFIELD = ZoneIds.BATTLEFIELD
    private const val ZONE_EXILE = ZoneIds.EXILE
    private const val ZONE_LIMBO = ZoneIds.LIMBO
    private const val ZONE_P1_HAND = ZoneIds.P1_HAND
    private const val ZONE_P1_GRAVEYARD = ZoneIds.P1_GRAVEYARD
    private const val ZONE_P1_LIBRARY = ZoneIds.P1_LIBRARY
    private const val ZONE_P2_HAND = ZoneIds.P2_HAND
    private const val ZONE_P2_LIBRARY = ZoneIds.P2_LIBRARY
    private const val ZONE_P2_GRAVEYARD = ZoneIds.P2_GRAVEYARD

    /**
     * Record of a zone transfer after ID reallocation.
     * Produced by [detectZoneTransfers], consumed by [annotationsForTransfer].
     * All fields are plain ints/strings — no Forge engine references, independently testable.
     */
    data class AppliedTransfer(
        val origId: Int,
        val newId: Int,
        val category: TransferCategory,
        val srcZoneId: Int,
        val destZoneId: Int,
        val grpId: Int,
        val ownerSeatId: Int,
    )

    /**
     * Result of Stage 1 zone-transfer detection.
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
     * Stage 1: Detect zone transfers and realloc instanceIds.
     *
     * Returns a [TransferResult] with patched copies of objects/zones.
     * Does not mutate [gameObjects] or [zones]. Calls [IdMapping.reallocInstanceId]
     * for ID allocation but defers tracking side effects (retireToLimbo, recordZone)
     * to the caller via the result.
     */
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        bridge: GameBridge,
        events: List<GameEvent>,
    ): TransferResult {
        val patchedObjects = gameObjects.toMutableList()
        val patchedZones = zones.toMutableList()
        val transfers = mutableListOf<AppliedTransfer>()
        val retiredIds = mutableListOf<Int>()
        val zoneRecordings = mutableListOf<Pair<Int, Int>>()

        for (i in patchedObjects.indices) {
            val obj = patchedObjects[i]
            val prevZone = bridge.getPreviousZone(obj.instanceId)
            if (prevZone != null && prevZone != obj.zoneId) {
                val forgeCardId = bridge.getForgeCardId(obj.instanceId)
                val category = if (forgeCardId != null && events.isNotEmpty()) {
                    AnnotationBuilder.categoryFromEvents(forgeCardId, events)
                        ?: inferCategory(obj, prevZone, obj.zoneId)
                } else {
                    inferCategory(obj, prevZone, obj.zoneId)
                }
                // Allocate new instanceId for zone transfer (real server does this).
                // Exception: Resolve (Stack→Battlefield) keeps the same instanceId.
                val realloc = if (category != TransferCategory.Resolve && forgeCardId != null) {
                    bridge.reallocInstanceId(forgeCardId)
                } else {
                    InstanceIdRegistry.IdReallocation(obj.instanceId, obj.instanceId)
                }
                val origId = realloc.old
                val newId = realloc.new
                log.debug("zone transfer: iid {} → {} category={}", origId, newId, category)
                // Patch gameObject and zone with new instanceId
                if (newId != origId) {
                    patchedObjects[i] = obj.toBuilder().setInstanceId(newId).build()
                    patchZoneInstanceId(patchedZones, obj.zoneId, origId, newId)
                    retiredIds.add(origId)
                    appendToZone(patchedZones, ZONE_LIMBO, origId)
                }
                transfers.add(
                    AppliedTransfer(origId, newId, category, prevZone, obj.zoneId, obj.grpId, obj.ownerSeatId),
                )
                zoneRecordings.add(newId to obj.zoneId)
            } else {
                zoneRecordings.add(obj.instanceId to obj.zoneId)
            }
        }

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

    /**
     * Stage 2: Generate annotations for a single zone transfer.
     * **Pure function** — no bridge access, no side effects. Independently testable.
     *
     * Returns (transient annotations, persistent annotations).
     */
    fun annotationsForTransfer(
        transfer: AppliedTransfer,
        actingSeat: Int,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val (origId, newId, category, srcZone, destZone, grpId, _) = transfer
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        when (category) {
            TransferCategory.PlayLand -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 3))
            }
            TransferCategory.CastSpell -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.abilityInstanceCreated(newId, sourceZoneId = srcZone))
                annotations.add(AnnotationBuilder.manaPaid(newId)) // TODO: pass manaId/color when mana payment tracking is wired
                annotations.add(AnnotationBuilder.abilityInstanceDeleted(newId))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 1))
            }
            TransferCategory.Resolve -> {
                annotations.add(AnnotationBuilder.resolutionStart(newId, grpId))
                annotations.add(AnnotationBuilder.resolutionComplete(newId, grpId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, actingSeat))
            }
            TransferCategory.Destroy, TransferCategory.Sacrifice, TransferCategory.Countered,
            TransferCategory.Bounce, TransferCategory.Draw, TransferCategory.Discard,
            TransferCategory.Mill, TransferCategory.Exile, TransferCategory.Return,
            TransferCategory.Search, TransferCategory.Put, TransferCategory.ZoneTransfer,
            -> {
                if (origId != newId) {
                    annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                }
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
            }
        }

        // Persistent: EnteredZoneThisTurn for cards landing on battlefield
        if (destZone == ZONE_BATTLEFIELD) {
            persistent.add(AnnotationBuilder.enteredZoneThisTurn(ZONE_BATTLEFIELD, newId))
        }

        return annotations to persistent
    }

    /**
     * Stage 3: Generate combat damage annotations.
     * **Pure function** given game state and previous player info.
     * No bridge mutation — only reads instanceId mappings.
     */
    internal fun combatAnnotations(
        game: Game,
        bridge: GameBridge,
    ): List<AnnotationInfo> {
        val handler = game.phaseHandler
        if (handler.phase != PhaseType.COMBAT_DAMAGE && handler.phase != PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) {
            return emptyList()
        }
        val combat = handler.combat ?: return emptyList()
        if (combat.attackers.isEmpty()) return emptyList()

        val annotations = mutableListOf<AnnotationInfo>()

        for (attacker in combat.attackers) {
            val iid = bridge.getOrAllocInstanceId(attacker.id)
            val dmg = attacker.getTotalDamageDoneBy()
            if (dmg > 0) {
                annotations.add(AnnotationBuilder.damageDealt(iid, dmg))
            }
            // Mark attacker as damaged if it took damage from blockers
            if (attacker.getDamage() > 0) {
                annotations.add(AnnotationBuilder.damagedThisTurn(iid))
            }
            // Mark blockers that took damage from this attacker
            // TODO: a blocker blocking multiple attackers may get duplicate annotations here
            for (blocker in combat.getBlockers(attacker)) {
                if (blocker.getDamage() > 0) {
                    val blockerIid = bridge.getOrAllocInstanceId(blocker.id)
                    annotations.add(AnnotationBuilder.damagedThisTurn(blockerIid))
                }
            }
        }

        val prev = bridge.getPreviousState()
        if (prev != null) {
            for (playerInfo in prev.playersList) {
                val seat = playerInfo.systemSeatNumber
                val player = bridge.getPlayer(seat)
                if (player != null) {
                    val delta = player.life - playerInfo.lifeTotal
                    if (delta != 0) {
                        annotations.add(AnnotationBuilder.modifiedLife(seat, delta))
                    }
                }
            }
        }

        val human = bridge.getPlayer(1)
        val activeSeat = if (handler.playerTurn == human) 1 else 2
        val protoPhase = PlayerMapper.mapPhase(handler.phase).number
        val protoStep = PlayerMapper.mapStep(handler.phase).number
        annotations.add(AnnotationBuilder.phaseOrStepModified(activeSeat, protoPhase, protoStep))
        // SyntheticEvent suppressed: real server sends it empty (no affectedIds/affectorId)
        // but our client version's SyntheticEventAnnotationParser crashes on missing affectedId.
        // Cosmetic-only (combat phase marker) — safe to omit.
        return annotations
    }

    /**
     * Result of Stage 4 mechanic annotation generation.
     * Separates transient (numbered per-GSM) from persistent (stable IDs) annotations.
     */
    data class MechanicAnnotationResult(
        val transient: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        /** Forge card IDs of auras/equipment that were detached this GSM. */
        val detachedForgeCardIds: List<Int> = emptyList(),
    )

    /**
     * Stage 4: Generate standalone annotations for mechanic events (Group B + A+).
     *
     * These are NOT zone-transfer annotations — they appear alongside zone transfers
     * in the same GSM. Processes events that Stage 1-2 ignore: counters, shuffle,
     * scry, surveil, token creation, attachments.
     *
     * **Pure function** — uses [idResolver] to map forgeCardId → instanceId.
     * Returns [MechanicAnnotationResult] with both transient and persistent annotations.
     */
    fun mechanicAnnotations(
        events: List<GameEvent>,
        idResolver: (Int) -> Int,
    ): MechanicAnnotationResult {
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()
        val detachedForgeCardIds = mutableListOf<Int>()
        for (ev in events) {
            when (ev) {
                is GameEvent.CountersChanged -> {
                    val delta = ev.newCount - ev.oldCount
                    if (delta == 0) continue
                    val instanceId = idResolver(ev.forgeCardId)
                    if (delta > 0) {
                        annotations.add(AnnotationBuilder.counterAdded(instanceId, ev.counterType, delta))
                    } else {
                        annotations.add(AnnotationBuilder.counterRemoved(instanceId, ev.counterType, -delta))
                    }
                    // Persistent: Counter state annotation with current count
                    persistent.add(AnnotationBuilder.counter(instanceId, AnnotationBuilder.counterTypeId(ev.counterType), ev.newCount))
                    log.debug("mechanic: counter {} {} on iid={}", if (delta > 0) "added" else "removed", ev.counterType, instanceId)
                }
                is GameEvent.LibraryShuffled -> {
                    // TODO: re-enable once LibraryShuffled carries pre/post instanceId lists
                    // annotations.add(AnnotationBuilder.shuffle(ev.seatId))
                    // Suppressed: client's ShuffleAnnotationParser requires OldIds/NewIds
                    // detail keys we don't have. Shuffle is cosmetic (animation only).
                    log.debug("mechanic: shuffle seat={} (suppressed — no detail keys)", ev.seatId)
                }
                is GameEvent.Scry -> {
                    annotations.add(AnnotationBuilder.scry(ev.seatId, ev.topCount, ev.bottomCount))
                    log.debug("mechanic: scry seat={} top={} bottom={}", ev.seatId, ev.topCount, ev.bottomCount)
                }
                is GameEvent.Surveil -> {
                    // Surveil is mechanically similar to scry — use scry annotation
                    // with surveil semantics (toLibrary = top, toGraveyard = bottom)
                    annotations.add(AnnotationBuilder.scry(ev.seatId, ev.toLibrary, ev.toGraveyard))
                    log.debug("mechanic: surveil seat={} lib={} gy={}", ev.seatId, ev.toLibrary, ev.toGraveyard)
                }
                is GameEvent.TokenCreated -> {
                    val instanceId = idResolver(ev.forgeCardId)
                    annotations.add(AnnotationBuilder.tokenCreated(instanceId))
                    log.debug("mechanic: tokenCreated iid={}", instanceId)
                }
                is GameEvent.TokenDestroyed -> {
                    val instanceId = idResolver(ev.forgeCardId)
                    annotations.add(AnnotationBuilder.tokenDeleted(instanceId))
                    log.debug("mechanic: tokenDeleted iid={}", instanceId)
                }
                is GameEvent.CardTapped -> {
                    val instanceId = idResolver(ev.forgeCardId)
                    annotations.add(AnnotationBuilder.tappedUntappedPermanent(instanceId, instanceId, ev.tapped))
                    log.debug("mechanic: tapped={} iid={}", ev.tapped, instanceId)
                }
                is GameEvent.PowerToughnessChanged -> {
                    val instanceId = idResolver(ev.forgeCardId)
                    if (ev.oldPower != ev.newPower) {
                        annotations.add(AnnotationBuilder.modifiedPower(instanceId))
                    }
                    if (ev.oldToughness != ev.newToughness) {
                        annotations.add(AnnotationBuilder.modifiedToughness(instanceId))
                    }
                    // P/T modification event for buff animation
                    val powerDelta = ev.newPower - ev.oldPower
                    val toughnessDelta = ev.newToughness - ev.oldToughness
                    if (powerDelta != 0 || toughnessDelta != 0) {
                        annotations.add(AnnotationBuilder.powerToughnessModCreated(instanceId, powerDelta, toughnessDelta))
                    }
                    log.debug("mechanic: P/T changed iid={} {}/{}→{}/{}", instanceId, ev.oldPower, ev.oldToughness, ev.newPower, ev.newToughness)
                }
                is GameEvent.CardAttached -> {
                    val auraIid = idResolver(ev.forgeCardId)
                    val targetIid = idResolver(ev.targetForgeId)
                    annotations.add(AnnotationBuilder.attachmentCreated(auraIid, targetIid))
                    persistent.add(AnnotationBuilder.attachment(auraIid, targetIid))
                    log.debug("mechanic: attachment aura={} target={}", auraIid, targetIid)
                }
                is GameEvent.CardDetached -> {
                    val auraIid = idResolver(ev.forgeCardId)
                    annotations.add(AnnotationBuilder.removeAttachment(auraIid))
                    detachedForgeCardIds.add(ev.forgeCardId)
                    log.debug("mechanic: removeAttachment aura={}", auraIid)
                }
                is GameEvent.CardsRevealed -> {
                    for (forgeCardId in ev.forgeCardIds) {
                        val instanceId = idResolver(forgeCardId)
                        annotations.add(AnnotationBuilder.revealedCardCreated(instanceId))
                        log.debug("mechanic: revealedCardCreated iid={} seat={}", instanceId, ev.ownerSeatId)
                    }
                }
                else -> {} // Zone-transfer events handled in Stages 1-2, combat in Stage 3
            }
        }
        return MechanicAnnotationResult(annotations, persistent, detachedForgeCardIds)
    }

    /** Infer category for a zone transfer annotation from zone IDs. */
    fun inferCategory(obj: GameObjectInfo, srcZone: Int, destZone: Int): TransferCategory =
        when {
            srcZone == ZONE_P1_HAND || srcZone == ZONE_P2_HAND -> when (destZone) {
                ZONE_STACK -> TransferCategory.CastSpell
                ZONE_BATTLEFIELD -> TransferCategory.PlayLand
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZONE_STACK && destZone == ZONE_BATTLEFIELD -> TransferCategory.Resolve
            srcZone == ZONE_BATTLEFIELD -> when (destZone) {
                ZONE_P1_GRAVEYARD, ZONE_P2_GRAVEYARD -> TransferCategory.Destroy
                ZONE_EXILE -> TransferCategory.Exile
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZONE_P1_LIBRARY || srcZone == ZONE_P2_LIBRARY -> when (destZone) {
                ZONE_BATTLEFIELD -> TransferCategory.Search
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZONE_P1_GRAVEYARD || srcZone == ZONE_P2_GRAVEYARD -> when (destZone) {
                ZONE_P1_HAND, ZONE_P2_HAND, ZONE_BATTLEFIELD -> TransferCategory.Return
                ZONE_EXILE -> TransferCategory.Exile
                else -> TransferCategory.ZoneTransfer
            }
            srcZone == ZONE_EXILE -> when (destZone) {
                ZONE_P1_HAND, ZONE_P2_HAND, ZONE_BATTLEFIELD -> TransferCategory.Return
                else -> TransferCategory.ZoneTransfer
            }
            else -> TransferCategory.ZoneTransfer
        }

    /**
     * Stage 5: Generate layered effect lifecycle annotations from [EffectTracker.DiffResult].
     *
     * Pure function — converts diff results to proto annotations.
     * Returns (transient, persistent) matching the pipeline convention.
     */
    /**
     * Build LayeredEffect annotations for P/T boost changes.
     * [sourceAbilityResolver] maps cardInstanceId → sourceAbilityGRPID (nullable).
     * Used to drive ability-specific VFX (e.g. Prowess glow).
     */
    fun effectAnnotations(
        diff: EffectTracker.DiffResult,
        sourceAbilityResolver: ((Int) -> Int?)? = null,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        if (diff.created.isEmpty() && diff.destroyed.isEmpty()) {
            return emptyList<AnnotationInfo>() to emptyList()
        }

        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        for (effect in diff.created) {
            val sourceAbilityGrpId = sourceAbilityResolver?.invoke(effect.cardInstanceId)

            // Transient: LayeredEffectCreated with affectorId = card instance
            transient.add(
                AnnotationBuilder.layeredEffectCreated(
                    effectId = effect.syntheticId,
                    affectorId = effect.cardInstanceId,
                ),
            )

            // Transient companion: PowerToughnessModCreated (drives buff animation)
            if (effect.powerDelta != 0 || effect.toughnessDelta != 0) {
                transient.add(
                    AnnotationBuilder.powerToughnessModCreated(
                        instanceId = effect.cardInstanceId,
                        power = effect.powerDelta,
                        toughness = effect.toughnessDelta,
                        affectorId = effect.cardInstanceId,
                    ),
                )
            }

            // Persistent: multi-typed [ModifiedToughness, ModifiedPower, LayeredEffect]
            // No LayeredEffectType for P/T buffs — real server only uses that for CopyObject
            persistent.add(
                AnnotationBuilder.layeredEffect(
                    instanceId = effect.cardInstanceId,
                    effectId = effect.syntheticId,
                    powerDelta = effect.powerDelta,
                    toughnessDelta = effect.toughnessDelta,
                    affectorId = effect.cardInstanceId,
                    sourceAbilityGrpId = sourceAbilityGrpId,
                ),
            )
        }

        for (effect in diff.destroyed) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(effect.syntheticId))
        }

        return transient to persistent
    }

    // --- helpers ---

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
}
