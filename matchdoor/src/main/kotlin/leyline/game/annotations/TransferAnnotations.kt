package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

/**
 * Stage 2 of the annotation pipeline: generate annotations for zone transfers.
 *
 * Pure function — no bridge access, no side effects. Independently testable.
 */
object TransferAnnotations {
    /**
     * ManaPaid.id base value. Protocol uses sequential mana payment IDs
     * across the GSM. CastSpell payments typically start at id=3 (after prior
     * persistent annotation IDs 1-2). Best-effort approximation — a proper fix
     * would track a global counter across the GSM.
     */
    private const val MANA_ID_BASE = 3

    /**
     * Generate annotations for a single zone transfer.
     * **Pure function** — no bridge access, no side effects. Independently testable.
     *
     * Returns (transient annotations, persistent annotations).
     */
    fun annotationsForTransfer(
        transfer: AppliedTransfer,
        actingSeat: SeatId,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val origId = InstanceId(transfer.origId)
        val newId = InstanceId(transfer.newId)
        val category = transfer.category
        val srcZone = transfer.srcZoneId
        val destZone = transfer.destZoneId
        val grpId = GrpId(transfer.grpId)
        val affectorId = if (transfer.affectorId != 0) InstanceId(transfer.affectorId) else null
        val altCostGrpId = GrpId(transfer.altCostAbilityGrpId)
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        when (category) {
            TransferCategory.PlayLand -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = ActionType.Play_add3))
            }
            TransferCategory.CastSpell -> {
                // Cast-time content split: OIC + ZT ride the announcement frame
                // (which is the targeting prompt frame for targeted spells, or the
                // full cast frame for untargeted ones). The mana-payment block and
                // the cast-action UAT are emitted from the GameEvent.SpellCast
                // handler in MechanicAnnotations — that handler runs on whichever
                // drain Forge produces the populated SpellCast event in (same drain
                // as the zone-change for untargeted spells; the post-target-submit
                // drain for targeted spells, when Forge has actually paid mana).
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
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
                    annotations.add(
                        AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId),
                    )
                }
            }
            TransferCategory.Destroy, TransferCategory.Countered,
            TransferCategory.Bounce, TransferCategory.Draw, TransferCategory.Discard,
            TransferCategory.Mill, TransferCategory.Surveil, TransferCategory.Exile,
            TransferCategory.Foretell,
            TransferCategory.Return, TransferCategory.Search, TransferCategory.Put,
            TransferCategory.SbaLegendRule, TransferCategory.SbaUnattachedAura,
            TransferCategory.ZoneTransfer,
            -> {
                if (origId != newId) {
                    annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
                }
                annotations.add(
                    AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId),
                )
            }
        }

        // Persistent: EnteredZoneThisTurn for cards landing on battlefield or stack
        if (destZone == ZoneIds.BATTLEFIELD || destZone == ZoneIds.STACK) {
            persistent.add(AnnotationBuilder.enteredZoneThisTurn(destZone, newId))
        }

        // Persistent: CastingTimeOption variants (alt-cost / kicker / chooseX)
        // for the just-cast spell. Attached to the staged stack object;
        // deleted via diffDeletedPersistentAnnotationIds when the spell
        // leaves the stack — see leyline-ucbf for the resolver that lets a
        // PersistentAnnotationKind close the lifecycle cleanly.
        emitCastingTimeOptions(persistent, transfer, category, newId, altCostGrpId)

        // Persistent: ColorProduction for lands entering the battlefield
        if (category == TransferCategory.PlayLand && transfer.colorOrdinals.isNotEmpty()) {
            persistent.add(AnnotationBuilder.colorProduction(newId, transfer.colorOrdinals))
        }

        return annotations to persistent
    }

    /**
     * Emit the full mana-ability annotation bracket for a sacrifice-for-mana transfer.
     * Matches expected client-facing sequence: AbilityInstanceCreated → TappedUntapped →
     * ObjectIdChanged → ZoneTransfer(Sacrifice) → UserActionTaken(ActivateMana) → ManaPaid →
     * AbilityInstanceDeleted.
     *
     * Note: the tap-for-mana prelude runs first for every payment, then the ZT pair,
     * then the mana-consumed postlude per payment — the OIC+ZT is sandwiched between
     * the two halves (vs the CastSpell path, where OIC+ZT comes before the whole block).
     *
     * **Assumption:** the sacrificed object IS the mana source — we pass `origId` as
     * both the tap target and ManaPaid's `landInstanceId`, ignoring `mp.landInstanceId`.
     * Holds for Treasure / Clue / Blood tokens (self-sacrificing mana sources). Breaks
     * for effects where a non-source permanent is sacrificed as a cost and a *different*
     * permanent produces mana (e.g. Phyrexian Tower). No such call site exists today; if
     * one appears, use `InstanceId(mp.landInstanceId)` here.
     */

    /** Emit CastingTimeOption rows on a CastSpell transfer per the cast mode
     *  signals carried on [AppliedTransfer]. No-op for non-CastSpell transfers
     *  and for casts without any of the mode flags set. */
    private fun emitCastingTimeOptions(
        persistent: MutableList<AnnotationInfo>,
        transfer: AppliedTransfer,
        category: TransferCategory,
        newId: InstanceId,
        altCostGrpId: GrpId,
    ) {
        if (category != TransferCategory.CastSpell) return
        if (altCostGrpId.value != 0) {
            persistent.add(
                AnnotationBuilder.castingTimeOption(
                    stackInstanceId = newId,
                    type = CastingTimeOptionType.CastThroughAbility,
                    alternateCostGrpId = altCostGrpId,
                ),
            )
        }
        if (transfer.kickerAbilityGrpId != 0) {
            persistent.add(
                AnnotationBuilder.castingTimeOptionKicker(
                    stackInstanceId = newId,
                    kickerAbilityGrpId = GrpId(transfer.kickerAbilityGrpId),
                    castAbilityGrpId = GrpId(transfer.grpId),
                ),
            )
        }
        if (transfer.chosenX != 0) {
            persistent.add(
                AnnotationBuilder.castingTimeOptionChooseX(
                    stackInstanceId = newId,
                    value = transfer.chosenX,
                ),
            )
        }
    }

    private fun emitManaSacrificeBracket(
        annotations: MutableList<AnnotationInfo>,
        transfer: AppliedTransfer,
        actingSeat: SeatId,
    ) {
        val origId = InstanceId(transfer.origId)
        val newId = InstanceId(transfer.newId)
        for (mp in transfer.manaPayments) {
            emitManaTap(annotations, InstanceId(mp.manaAbilityInstanceId), origId, transfer.srcZoneId)
        }
        if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
        annotations.add(
            AnnotationBuilder.zoneTransfer(newId, transfer.srcZoneId, transfer.destZoneId, transfer.category.label),
        )
        for ((i, mp) in transfer.manaPayments.withIndex()) {
            emitManaConsume(annotations, i, mp, spellIid = InstanceId(mp.spellInstanceId), landIid = origId, actingSeat = actingSeat)
        }
    }

    /**
     * Tap-for-mana prelude: (AbilityInstanceCreated, TappedUntapped) for one land.
     * Used by both the CastSpell path and the Sacrifice path.
     */
    private fun emitManaTap(
        annotations: MutableList<AnnotationInfo>,
        manaAbilityIid: InstanceId,
        landIid: InstanceId,
        sourceZoneId: Int,
    ) {
        annotations.add(
            AnnotationBuilder.abilityInstanceCreated(
                abilityInstanceId = manaAbilityIid,
                affectorId = landIid,
                sourceZoneId = sourceZoneId,
            ),
        )
        annotations.add(
            AnnotationBuilder.tappedUntappedPermanent(permanentId = landIid, abilityId = manaAbilityIid),
        )
    }

    /**
     * Mana-consumed postlude: (UserActionTaken, ManaPaid, AbilityInstanceDeleted) for
     * one land's contribution to a spell or ability. [spellIid] is the consumer of
     * the mana, [landIid] is the producer.
     */
    private fun emitManaConsume(
        annotations: MutableList<AnnotationInfo>,
        index: Int,
        mp: ManaPaymentRecord,
        spellIid: InstanceId,
        landIid: InstanceId,
        actingSeat: SeatId,
    ) {
        val manaAbilityIid = InstanceId(mp.manaAbilityInstanceId)
        annotations.add(
            AnnotationBuilder.userActionTaken(
                instanceId = manaAbilityIid,
                seatId = actingSeat,
                actionType = ActionType.ActivateMana,
                abilityGrpId = GrpId(mp.abilityGrpId),
            ),
        )
        annotations.add(
            AnnotationBuilder.manaPaid(
                spellInstanceId = spellIid,
                landInstanceId = landIid,
                manaId = index + MANA_ID_BASE,
                color = mp.color,
            ),
        )
        annotations.add(AnnotationBuilder.abilityInstanceDeleted(manaAbilityIid, landIid))
    }

    /**
     * Cast-event annotations: per-payment mana bracket plus the cast-action UAT.
     *
     * Driven by [GameEvent.SpellCast] rather than [AppliedTransfer] so the bracket
     * lands on whichever drain Forge produces the populated event in:
     *
     * - Untargeted cast: SpellCast and the Hand→Stack ZoneChanged arrive in the
     *   same drain, so OIC + ZT (from [annotationsForTransfer]) and this bracket
     *   share one GSM.
     * - Targeted cast: SpellCast fires only after target submission and cost
     *   payment, so this bracket lands on the post-submit GSM
     *   (TARGETS_CONFIRMED), separate from the announce GSM (CAST_TARGETED)
     *   which carries only OIC + ZT + persistent.
     *
     * Ability gameObjects (triggered OR activated) are skipped — they ride
     * separate paths. Triggers go through [MechanicAnnotations]
     * (TriggeringObject + AbilityInstance lifecycle); activated abilities
     * don't currently emit a per-payment block from this function (pre-uh9
     * they got nothing here either, since they have no Hand→Stack zone
     * change). If activated-ability mana brackets need to be emitted in
     * the future, factor a separate event handler — don't reuse this one,
     * because the cast-action UAT keyed on the spell card's iid would land
     * against a battlefield-resident card and confuse the classifier.
     */
    internal fun castSpellEventAnnotations(
        ev: GameEvent.SpellCast,
        idResolver: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId,
    ): List<AnnotationInfo> {
        if (ev.isAbility) return emptyList()
        val annotations = mutableListOf<AnnotationInfo>()
        val spellIid = idResolver(ev.cardId)
        for ((i, mp) in ev.manaPayments.withIndex()) {
            val landIid = idResolver(mp.sourceCardId)
            val manaAbilityIid = idResolver(FrameIdResolver.manaAbilityForgeId(mp.sourceCardId))
            emitManaTap(annotations, manaAbilityIid, landIid, ZoneIds.BATTLEFIELD)
            annotations.add(
                AnnotationBuilder.userActionTaken(
                    instanceId = manaAbilityIid,
                    seatId = ev.seatId,
                    actionType = ActionType.ActivateMana,
                    abilityGrpId = manaAbilityGrpIdResolver(mp.sourceCardId),
                ),
            )
            annotations.add(
                AnnotationBuilder.manaPaid(
                    spellInstanceId = spellIid,
                    landInstanceId = landIid,
                    manaId = i + MANA_ID_BASE,
                    color = mp.color,
                ),
            )
            annotations.add(AnnotationBuilder.abilityInstanceDeleted(manaAbilityIid, landIid))
        }
        val castActionType = if (ev.isAdventure) ActionType.CastAdventure else ActionType.Cast
        val altCostGrpId = GrpId(ev.altCostAbilityGrpId)
        annotations.add(
            AnnotationBuilder.userActionTaken(
                instanceId = spellIid,
                seatId = ev.seatId,
                actionType = castActionType,
                // Alt-cost casts (Madness, Flashback, Warp, Cycling, Impending)
                // populate both fields with the alt-cost ability grpId.
                abilityGrpId = altCostGrpId,
                alternativeGrpId = altCostGrpId,
            ),
        )
        return annotations
    }
}
