package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.bridge.findCard
import leyline.game.mapper.ObjectMapper
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
@Suppress("LargeClass")
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

    /** Offset for mana ability instance IDs (separate from stack abilities at 100_000). */
    private const val MANA_ABILITY_ID_OFFSET = 200_000

    /**
     * ManaPaid.id base value. The real server assigns mana payment IDs sequentially
     * across the GSM. CastSpell payments typically start at id=3 (after prior
     * persistent annotation IDs 1-2). Best-effort approximation — a proper fix
     * would track a global counter across the GSM.
     */
    private const val MANA_ID_BASE = 3

    /**
     * Record of a zone transfer after ID reallocation.
     * Produced by [detectZoneTransfers], consumed by [annotationsForTransfer].
     * All fields are plain ints/strings — no Forge engine references, independently testable.
     */
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
     * Stage 1: Detect zone transfers — pure overload.
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
                val realloc = if (category != TransferCategory.Resolve && forgeCardId != null) {
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
                    appendToZone(patchedZones, ZONE_LIMBO, origId)
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

                // Extract mana payment info from SpellCast events for CastSpell transfers.
                val manaPayments = if (category == TransferCategory.CastSpell && forgeCardId != null) {
                    events.filterIsInstance<GameEvent.SpellCast>()
                        .firstOrNull { it.cardId == forgeCardId }
                        ?.manaPayments?.map { mp ->
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
                } else {
                    emptyList()
                }

                transfers.add(
                    AppliedTransfer(origId, newId, category, prevZone, obj.zoneId, obj.grpId, obj.ownerSeatId, affectorId, colorBitmasks, manaPayments),
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
        val origId = transfer.origId
        val newId = transfer.newId
        val category = transfer.category
        val srcZone = transfer.srcZoneId
        val destZone = transfer.destZoneId
        val grpId = transfer.grpId
        val affectorId = transfer.affectorId
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
                // Per-land mana payment block (repeats for each land tapped)
                for ((i, mp) in transfer.manaPayments.withIndex()) {
                    annotations.add(
                        AnnotationBuilder.abilityInstanceCreated(
                            abilityInstanceId = mp.manaAbilityInstanceId,
                            affectorId = mp.landInstanceId,
                            sourceZoneId = ZONE_BATTLEFIELD,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.tappedUntappedPermanent(
                            permanentId = mp.landInstanceId,
                            abilityId = mp.manaAbilityInstanceId,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.userActionTaken(
                            instanceId = mp.manaAbilityInstanceId,
                            seatId = actingSeat,
                            actionType = 4,
                            abilityGrpId = mp.abilityGrpId,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.manaPaid(
                            spellInstanceId = newId,
                            landInstanceId = mp.landInstanceId,
                            manaId = i + MANA_ID_BASE,
                            color = mp.color,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.abilityInstanceDeleted(
                            abilityInstanceId = mp.manaAbilityInstanceId,
                            affectorId = mp.landInstanceId,
                        ),
                    )
                }
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 1))
            }
            TransferCategory.Resolve -> {
                annotations.add(AnnotationBuilder.resolutionStart(newId, grpId))
                annotations.add(AnnotationBuilder.resolutionComplete(newId, grpId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, actingSeat))
            }
            TransferCategory.Sacrifice -> {
                if (transfer.manaPayments.isNotEmpty()) {
                    emitManaSacrificeBracket(annotations, transfer, actingSeat)
                } else {
                    if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
                    annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId))
                }
            }
            TransferCategory.Destroy, TransferCategory.Countered,
            TransferCategory.Bounce, TransferCategory.Draw, TransferCategory.Discard,
            TransferCategory.Mill, TransferCategory.Surveil, TransferCategory.Exile,
            TransferCategory.Return, TransferCategory.Search, TransferCategory.Put,
            TransferCategory.SbaLegendRule, TransferCategory.ZoneTransfer,
            -> {
                if (origId != newId) {
                    annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
                }
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId))
            }
        }

        // Persistent: EnteredZoneThisTurn for cards landing on battlefield or stack
        if (destZone == ZONE_BATTLEFIELD || destZone == ZONE_STACK) {
            persistent.add(AnnotationBuilder.enteredZoneThisTurn(destZone, newId))
        }

        // Persistent: ColorProduction for lands entering the battlefield
        if (category == TransferCategory.PlayLand && transfer.colorBitmasks.isNotEmpty()) {
            persistent.add(AnnotationBuilder.colorProduction(newId, transfer.colorBitmasks))
        }

        return annotations to persistent
    }

    /**
     * Stage 3: Generate combat damage annotations.
     * **Pure function** given game state and previous player info.
     * No bridge mutation — only reads instanceId mappings.
     */
    /**
     * Result of combat damage annotation generation.
     * [hasCombatDamage] signals that turnInfo should be overridden to CombatDamage.
     */
    data class CombatAnnotationResult(
        val annotations: List<AnnotationInfo>,
        val hasCombatDamage: Boolean = false,
    )

    /**
     * Stage 3: Generate combat damage annotations from events.
     *
     * Uses [GameEvent.DamageDealtToCard] and [GameEvent.DamageDealtToPlayer] events
     * captured synchronously on the engine thread (before Forge clears combat state).
     * The combat object's attackers list is empty by the time we build the GSM,
     * so we cannot query it here.
     *
     * Annotation ordering matches real server: PhaseOrStepModified → DamageDealt(s)
     * → SyntheticEvent → ModifiedLife → (ObjectIdChanged/ZoneTransfer handled by Stage 1).
     *
     * Delegates to the pure overload, adapting [GameBridge] calls to function parameters.
     */
    internal fun combatAnnotations(
        events: List<GameEvent>,
        bridge: GameBridge,
    ): CombatAnnotationResult {
        val prev = bridge.getDiffBaselineState()
        val previousLifeTotals = prev?.playersList
            ?.associate { it.systemSeatNumber to it.lifeTotal } ?: emptyMap()
        val currentLifeTotals = previousLifeTotals.keys.associateWith { seat ->
            bridge.getPlayer(SeatId(seat))?.life ?: 0
        }
        return combatAnnotations(
            events = events,
            idResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
            previousLifeTotals = previousLifeTotals,
            currentLifeTotals = currentLifeTotals,
        )
    }

    /**
     * Stage 3: Generate combat damage annotations — pure overload.
     * Takes function parameters instead of [GameBridge] for independent testability.
     *
     * [idResolver] maps forgeCardId → instanceId.
     * [previousLifeTotals] is seatId → life total from previous GSM baseline.
     * [currentLifeTotals] is seatId → current life total from engine.
     */
    internal fun combatAnnotations(
        events: List<GameEvent>,
        idResolver: (ForgeCardId) -> InstanceId,
        previousLifeTotals: Map<Int, Int>,
        currentLifeTotals: Map<Int, Int>,
    ): CombatAnnotationResult {
        val cardDamage = events.filterIsInstance<GameEvent.DamageDealtToCard>()
        val playerDamage = events.filterIsInstance<GameEvent.DamageDealtToPlayer>()
        if (cardDamage.isEmpty() && playerDamage.isEmpty()) return CombatAnnotationResult(emptyList())

        val annotations = mutableListOf<AnnotationInfo>()

        // PhaseOrStepModified is now emitted from GameEvent.PhaseChanged in Stage 2b.
        // CombatDamage phase fires via GameEventTurnPhase before damage events.

        // --- DamageDealt: creature → creature ---
        for (ev in cardDamage) {
            val sourceIid = idResolver(ev.sourceCardId).value
            val targetIid = idResolver(ev.targetCardId).value
            annotations.add(AnnotationBuilder.damageDealt(sourceIid, targetId = targetIid, ev.amount))
        }

        // --- DamageDealt: creature → player ---
        var firstPlayerDamageAttackerIid: Int? = null
        var playerDamageSeat: Int? = null
        for (ev in playerDamage) {
            val sourceIid = idResolver(ev.sourceCardId).value
            annotations.add(AnnotationBuilder.damageDealt(sourceIid, targetId = ev.targetSeatId.value, ev.amount))
            if (firstPlayerDamageAttackerIid == null) firstPlayerDamageAttackerIid = sourceIid
            playerDamageSeat = ev.targetSeatId.value
        }

        // --- DamagedThisTurn badges ---
        for (ev in cardDamage) {
            val targetIid = idResolver(ev.targetCardId).value
            annotations.add(AnnotationBuilder.damagedThisTurn(targetIid))
        }

        // --- SyntheticEvent when player takes combat damage ---
        if (playerDamageSeat != null && firstPlayerDamageAttackerIid != null) {
            annotations.add(AnnotationBuilder.syntheticEvent(firstPlayerDamageAttackerIid, playerDamageSeat))
        }

        // --- ModifiedLife from baseline comparison ---
        for ((seat, prevLife) in previousLifeTotals) {
            val currentLife = currentLifeTotals[seat] ?: continue
            val delta = currentLife - prevLife
            if (delta != 0) {
                annotations.add(AnnotationBuilder.modifiedLife(seat, delta, affectorId = firstPlayerDamageAttackerIid ?: 0))
            }
        }

        return CombatAnnotationResult(annotations, hasCombatDamage = true)
    }

    /**
     * Result of Stage 4 mechanic annotation generation.
     * Separates transient (numbered per-GSM) from persistent (stable IDs) annotations.
     */
    data class MechanicAnnotationResult(
        val transient: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        /** Forge card IDs of auras/equipment that were detached this GSM. */
        val detachedForgeCardIds: List<ForgeCardId> = emptyList(),
        /** Forge card IDs of permanents that left the battlefield this GSM.
         *  Used by [PersistentAnnotationStore.computeBatch] to clean up
         *  [AnnotationType.DisplayCardUnderCard] persistent annotations. */
        val exileSourceLeftPlayForgeCardIds: List<ForgeCardId> = emptyList(),
        /** Controller-change effects created this GSM (for persistent tracking). */
        val controllerChangedEffects: List<ControllerChangedEffect> = emptyList(),
        /** Forge card IDs of permanents whose control reverted this GSM. */
        val controllerRevertedForgeCardIds: List<ForgeCardId> = emptyList(),
        /** AbilityWordActive annotations from scanner — full replacement set for this GSM. */
        val abilityWordPersistent: List<AnnotationInfo> = emptyList(),
    ) {
        /** Tracks an active controller-change effect for persistent annotation lifecycle. */
        data class ControllerChangedEffect(
            val forgeCardId: ForgeCardId,
            val effectId: Int,
            val affectorInstanceId: Int,
            val stolenInstanceId: Int,
        )
    }

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
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun mechanicAnnotations(
        events: List<GameEvent>,
        manaPaidForgeCardIds: Set<ForgeCardId> = emptySet(),
        idResolver: (ForgeCardId) -> InstanceId,
        effectIdAllocator: () -> Int = { 0 },
        activeStealForgeCardIds: Set<ForgeCardId> = emptySet(),
    ): MechanicAnnotationResult {
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()
        val detachedForgeCardIds = mutableListOf<ForgeCardId>()
        val exileSourceLeftPlayForgeCardIds = mutableListOf<ForgeCardId>()
        val controllerChangedEffects = mutableListOf<MechanicAnnotationResult.ControllerChangedEffect>()
        val controllerRevertedForgeCardIds = mutableListOf<ForgeCardId>()
        for (ev in events) {
            when (ev) {
                is GameEvent.CountersChanged -> {
                    val delta = ev.newCount - ev.oldCount
                    if (delta == 0) continue
                    val instanceId = idResolver(ev.cardId).value
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
                    log.debug("mechanic: shuffle seat={} (suppressed — no detail keys)", ev.seatId.value)
                }
                is GameEvent.Scry -> {
                    annotations.add(AnnotationBuilder.scry(ev.seatId.value, ev.topCount, ev.bottomCount))
                    log.debug("mechanic: scry seat={} top={} bottom={}", ev.seatId.value, ev.topCount, ev.bottomCount)
                }
                is GameEvent.Surveil -> {
                    // Surveil is mechanically similar to scry — use scry annotation
                    // with surveil semantics (toLibrary = top, toGraveyard = bottom)
                    annotations.add(AnnotationBuilder.scry(ev.seatId.value, ev.toLibrary, ev.toGraveyard))
                    log.debug("mechanic: surveil seat={} lib={} gy={}", ev.seatId.value, ev.toLibrary, ev.toGraveyard)
                }
                is GameEvent.TokenCreated -> {
                    val instanceId = idResolver(ev.cardId).value
                    annotations.add(AnnotationBuilder.tokenCreated(instanceId))
                    log.debug("mechanic: tokenCreated iid={}", instanceId)
                }
                is GameEvent.TokenDestroyed -> {
                    val instanceId = idResolver(ev.cardId).value
                    annotations.add(AnnotationBuilder.tokenDeleted(instanceId))
                    log.debug("mechanic: tokenDeleted iid={}", instanceId)
                }
                is GameEvent.CardTapped -> {
                    if (ev.cardId in manaPaidForgeCardIds) {
                        log.debug("mechanic: skipping tapped for mana-paid land forgeId={}", ev.cardId)
                    } else {
                        val instanceId = idResolver(ev.cardId).value
                        annotations.add(AnnotationBuilder.tappedUntappedPermanent(instanceId, instanceId, ev.tapped))
                        log.debug("mechanic: tapped={} iid={}", ev.tapped, instanceId)
                    }
                }
                is GameEvent.PowerToughnessChanged -> {
                    val instanceId = idResolver(ev.cardId).value
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
                    val auraIid = idResolver(ev.cardId).value
                    val targetIid = idResolver(ev.targetCardId).value
                    annotations.add(AnnotationBuilder.attachmentCreated(auraIid, targetIid))
                    persistent.add(AnnotationBuilder.attachment(auraIid, targetIid))
                    log.debug("mechanic: attachment aura={} target={}", auraIid, targetIid)
                }
                is GameEvent.CardDetached -> {
                    val auraIid = idResolver(ev.cardId).value
                    annotations.add(AnnotationBuilder.removeAttachment(auraIid))
                    detachedForgeCardIds.add(ev.cardId)
                    log.debug("mechanic: removeAttachment aura={}", auraIid)
                }
                is GameEvent.CardsRevealed -> {
                    for (cardId in ev.cardIds) {
                        val instanceId = idResolver(cardId).value
                        annotations.add(AnnotationBuilder.revealedCardCreated(instanceId))
                        log.debug("mechanic: revealedCardCreated iid={} seat={}", instanceId, ev.ownerSeatId)
                    }
                }
                // Track permanents leaving battlefield for DisplayCardUnderCard cleanup.
                // CardExiled is safe to add unconditionally — findExileSourcesLeavingPlay
                // only matches cards that were an exile source (affectorId), not exiled cards.
                is GameEvent.CardDestroyed -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.CardSacrificed -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.CardBounced -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.CardExiled -> {
                    val sourceId = ev.sourceCardId
                    // Only render "exiled under this card" for BF→Exile (e.g. Fiend Hunter).
                    // GY→Exile (e.g. Predator trigger) should go to the exile zone normally.
                    if (sourceId != null && ev.fromBattlefield) {
                        val sourceIid = idResolver(sourceId).value
                        val exiledIid = idResolver(ev.cardId).value
                        persistent.add(AnnotationBuilder.displayCardUnderCard(affectorId = sourceIid, instanceId = exiledIid))
                        log.debug("mechanic: displayCardUnderCard source={} exiled={}", sourceIid, exiledIid)
                    }
                    exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                }
                is GameEvent.ControllerChanged -> {
                    val cardIid = idResolver(ev.cardId).value
                    val isRevert = ev.cardId in activeStealForgeCardIds

                    if (isRevert) {
                        // Control reverted — signal cleanup of existing CC persistent annotation
                        controllerRevertedForgeCardIds.add(ev.cardId)
                        log.debug(
                            "mechanic: controllerChanged revert iid={} {}->{}",
                            cardIid,
                            ev.oldControllerSeatId,
                            ev.newControllerSeatId,
                        )
                    } else {
                        // New steal: emit transient + persistent + track effect.
                        // Walk events backward from this ControllerChanged to find the nearest
                        // preceding SpellResolved — handles multiple spells in one GSM.
                        val evIndex = events.indexOf(ev)
                        val spellResolved = events.subList(0, evIndex)
                            .filterIsInstance<GameEvent.SpellResolved>()
                            .lastOrNull()
                        val affectorIid = if (spellResolved != null) {
                            idResolver(spellResolved.cardId).value
                        } else {
                            0
                        }
                        val effectId = effectIdAllocator()
                        annotations.add(AnnotationBuilder.layeredEffectCreated(effectId, affectorIid))
                        annotations.add(AnnotationBuilder.controllerChanged(affectorIid, cardIid))
                        persistent.add(AnnotationBuilder.controllerChangedEffect(affectorIid, cardIid, effectId))
                        controllerChangedEffects.add(MechanicAnnotationResult.ControllerChangedEffect(ev.cardId, effectId, affectorIid, cardIid))
                        log.debug(
                            "mechanic: controllerChanged steal iid={} affector={} effectId={} {}->{}",
                            cardIid,
                            affectorIid,
                            effectId,
                            ev.oldControllerSeatId,
                            ev.newControllerSeatId,
                        )
                    }
                }
                is GameEvent.LegendRuleDeath -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.ZoneChanged -> {
                    if (ev.from == Zone.Battlefield) exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                }
                else -> {} // Remaining zone-transfer events handled in Stages 1-2, combat in Stage 3
            }
        }
        return MechanicAnnotationResult(
            annotations,
            persistent,
            detachedForgeCardIds,
            exileSourceLeftPlayForgeCardIds,
            controllerChangedEffects,
            controllerRevertedForgeCardIds,
        )
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
     * Stage 5: Build LayeredEffect lifecycle annotations from [EffectTracker.DiffResult].
     *
     * Pure function — converts diff results to proto annotations.
     * Returns (transient, persistent) matching the pipeline convention.
     *
     * [sourceAbilityResolver] maps (cardInstanceId, staticId) → sourceAbilityGRPID.
     * The staticId is the Forge StaticAbility ID from the boost table — enables
     * per-ability resolution via AbilityRegistry (not just keyword heuristics).
     * Used to drive ability-specific VFX (e.g. Prowess glow).
     */
    fun effectAnnotations(
        diff: EffectTracker.DiffResult,
        sourceAbilityResolver: ((InstanceId, Long) -> Int?)? = null,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        if (diff.created.isEmpty() && diff.destroyed.isEmpty()) {
            return emptyList<AnnotationInfo>() to emptyList()
        }

        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        for (effect in diff.created) {
            val sourceAbilityGrpId = sourceAbilityResolver?.invoke(
                InstanceId(effect.cardInstanceId),
                effect.fingerprint.staticId,
            )

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
            if (zoneId != ZONE_BATTLEFIELD) continue
            if (instanceId in mainLoopOrigIds) continue
            val forgeCardId = forgeIdLookup(InstanceId(instanceId)) ?: continue
            val sacrificeEv = sacrificeEvents.firstOrNull { it.cardId == forgeCardId } ?: continue

            val stillOnBattlefield = instanceId in currentInstanceIds
            val realloc = idAllocator(forgeCardId)
            val origId = realloc.old.value
            val newId = realloc.new.value
            val ownerSeat = sacrificeEv.seatId
            val destZone = if (ownerSeat.value == 1) ZONE_P1_GRAVEYARD else ZONE_P2_GRAVEYARD

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
                removeFromZone(patchedZones, ZONE_BATTLEFIELD, instanceId)
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
                appendToZone(patchedZones, ZONE_LIMBO, origId)
            }

            transfers.add(
                AppliedTransfer(
                    origId, newId, TransferCategory.Sacrifice, ZONE_BATTLEFIELD, destZone,
                    resolvedGrpId, ownerSeat.value, manaPayments = manaPayments,
                ),
            )
            zoneRecordings.add(newId to destZone)
            log.debug("disappeared token: iid {} → {} category=Sacrifice manaPayments={}", origId, newId, manaPayments.size)
        }
    }

    /**
     * Emit the full mana-ability annotation bracket for a sacrifice-for-mana transfer.
     * Matches real server sequence: AbilityInstanceCreated → TappedUntapped →
     * ObjectIdChanged → ZoneTransfer(Sacrifice) → UserActionTaken(4) → ManaPaid →
     * AbilityInstanceDeleted.
     */
    private fun emitManaSacrificeBracket(
        annotations: MutableList<AnnotationInfo>,
        transfer: AppliedTransfer,
        actingSeat: Int,
    ) {
        val origId = transfer.origId
        val newId = transfer.newId
        for (mp in transfer.manaPayments) {
            annotations.add(AnnotationBuilder.abilityInstanceCreated(mp.manaAbilityInstanceId, origId, transfer.srcZoneId))
            annotations.add(AnnotationBuilder.tappedUntappedPermanent(origId, mp.manaAbilityInstanceId))
        }
        if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
        annotations.add(AnnotationBuilder.zoneTransfer(newId, transfer.srcZoneId, transfer.destZoneId, transfer.category.label))
        for ((i, mp) in transfer.manaPayments.withIndex()) {
            annotations.add(AnnotationBuilder.userActionTaken(mp.manaAbilityInstanceId, actingSeat, actionType = 4, abilityGrpId = mp.abilityGrpId))
            annotations.add(AnnotationBuilder.manaPaid(mp.spellInstanceId, origId, i + MANA_ID_BASE, mp.color))
            annotations.add(AnnotationBuilder.abilityInstanceDeleted(mp.manaAbilityInstanceId, origId))
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
