package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Result of mechanic annotation generation.
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
    /** Qualification annotations for adventure-exiled cards — full replacement set for this GSM. */
    val qualificationPersistent: List<AnnotationInfo> = emptyList(),
    /** CrewedThisTurn persistent annotations — full replacement set for this GSM. */
    val crewedThisTurnPersistent: List<AnnotationInfo> = emptyList(),
    /** ModifiedType+LayeredEffect persistent annotations for crew type changes — full replacement set. */
    val crewTypeChangePersistent: List<AnnotationInfo> = emptyList(),
    /** TemporaryPermanent pAnns for copy tokens with EOT-sacrifice — full replacement set. */
    val temporaryPermanentPersistent: List<AnnotationInfo> = emptyList(),
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
 * Stages 4–5 of the annotation pipeline: mechanic events + layered effect lifecycle.
 *
 * Stage 4: counters, shuffle, scry, surveil, tokens, attachments, controller change, etc.
 * Stage 5: LayeredEffect P/T boosts + keyword grants from [EffectTracker].
 *
 * Pure functions — no shared mutable state.
 * Extracted from [AnnotationPipeline] for independent maintainability.
 */
@Suppress("MemberNameEqualsClassName")
object MechanicAnnotations {
    private val log = LoggerFactory.getLogger(MechanicAnnotations::class.java)

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
        val qualificationPersistent = mutableListOf<AnnotationInfo>()
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
                is GameEvent.RevealProxiesDeleted -> {
                    for (proxyId in ev.proxyInstanceIds) {
                        annotations.add(AnnotationBuilder.revealedCardDeleted(proxyId.value))
                        log.debug("mechanic: revealedCardDeleted proxyIid={}", proxyId)
                    }
                }
                is GameEvent.CardTransformed -> {
                    // Emit Qualification pAnns for back-face keywords.
                    // Currently only Menace is mapped; table grows as more keywords are observed.
                    if (ev.isBackSide) {
                        val menace = KeywordQualifications.forKeyword("Menace")
                        if (menace != null) {
                            val instanceId = idResolver(ev.cardId).value
                            qualificationPersistent.add(
                                AnnotationBuilder.qualification(
                                    affectorId = instanceId,
                                    instanceId = instanceId,
                                    grpId = menace.grpId,
                                    qualificationType = menace.qualificationType,
                                    qualificationSubtype = menace.qualificationSubtype,
                                    sourceParent = instanceId,
                                ),
                            )
                            log.debug("mechanic: Qualification (Menace) on transform iid={}", instanceId)
                        }
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
            qualificationPersistent = qualificationPersistent,
        )
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
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        keywordAffectorResolver: ((String, Long, Long) -> Int)? = null,
        uniqueAbilityIdAllocator: (() -> Int)? = null,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val hasBoosts = diff.created.isNotEmpty() || diff.destroyed.isNotEmpty()
        val hasKeywords = keywordDiff.created.isNotEmpty() || keywordDiff.destroyed.isNotEmpty()
        if (!hasBoosts && !hasKeywords) {
            return emptyList<AnnotationInfo>() to emptyList()
        }

        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        // ── P/T boosts ──────────────────────────────────────────────────────
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

        // ── Keyword grants ──────────────────────────────────────────────────
        // Group created keyword effects by (keyword, timestamp, staticId) so that
        // all creatures affected by the same static ability get one shared pAnn.
        if (keywordDiff.created.isNotEmpty() && uniqueAbilityIdAllocator != null) {
            val groups = keywordDiff.created
                .groupBy { Triple(it.keyword, it.fingerprint.timestamp, it.fingerprint.staticId) }

            for ((key, effects) in groups) {
                val (keyword, timestamp, staticId) = key
                val grpId = KeywordGrpIds.forKeyword(keyword) ?: continue
                val effectId = effects.first().syntheticId
                val affectorId = keywordAffectorResolver?.invoke(keyword, timestamp, staticId) ?: 0

                transient.add(
                    AnnotationBuilder.layeredEffectCreated(effectId, if (affectorId != 0) affectorId else null),
                )

                val creatureIids = effects.map { it.cardInstanceId }
                val uniqueAbilityIds = creatureIids.map { uniqueAbilityIdAllocator() }

                persistent.add(
                    AnnotationBuilder.addAbilityMulti(
                        affectedIds = creatureIids,
                        grpId = grpId,
                        effectId = effectId,
                        uniqueAbilityIds = uniqueAbilityIds,
                        originalAbilityObjectZcid = affectorId,
                        affectorId = affectorId,
                    ),
                )

                log.debug(
                    "effectAnnotations: keyword grant {} grpId={} effectId={} creatures={}",
                    keyword,
                    grpId,
                    effectId,
                    creatureIids.size,
                )
            }
        }

        for (effect in keywordDiff.destroyed) {
            if (KeywordGrpIds.forKeyword(effect.keyword) == null) continue
            transient.add(AnnotationBuilder.layeredEffectDestroyed(effect.syntheticId))
        }

        return transient to persistent
    }
}
