package leyline.game.annotations

import leyline.game.codes.DetailKeys
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * One ordering rule for the annotation pipeline. Each rule contributes a list
 * of partial-order edges `(fromIndex, toIndex)` over the GSM's annotation list.
 * The enforcer collects edges from every rule in [OrderRules.all] and applies
 * a stable topological sort that preserves original index order when no
 * constraint exists.
 *
 * Adding a rule is one new `data object` plus one entry in [OrderRules.all].
 * The enforcer body never changes.
 */
sealed interface OrderRule {
    /** Stable identifier for diagnostics and toggling. */
    val name: String

    /**
     * Partial-order edges. Each pair `(i, j)` means "annotations[i] must
     * precede annotations[j] in the final order".
     */
    fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>>
}

object OrderRules {
    /**
     * Active rule set. Order in this list does not matter — edges from every
     * rule are merged before topological sort.
     */
    val all: List<OrderRule> =
        listOf(
            ObjectIdChangedFirstRule,
            SameCardIncrementalRule,
            TokenCreatedFirstRule,
            PhaseOrStepFirstRule,
            ResolutionSandwichRule,
            DamageBeforeDeathRule,
            CombatDamageBlockRule,
        )
}

/**
 * Rule 1: ObjectIdChanged must precede any annotation referencing its new_id.
 *
 * The OIC handler populates the client identity map; downstream handlers expect
 * the mapping to exist when they encounter the new ID.
 */
data object ObjectIdChangedFirstRule : OrderRule {
    override val name: String = "object_id_changed_first"

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val newIdToOicIndex = mutableMapOf<Int, Int>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.ObjectIdChanged in ann.typeList) {
                val newId = ann.detailInt(DetailKeys.NEW_ID)
                if (newId != 0) newIdToOicIndex[newId] = i
            }
        }
        if (newIdToOicIndex.isEmpty()) return emptyList()

        val edges = mutableListOf<Pair<Int, Int>>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.ObjectIdChanged in ann.typeList) continue
            for (refId in referencedIds(ann)) {
                val oicIndex = newIdToOicIndex[refId] ?: continue
                edges.add(oicIndex to i) // OIC must come before this annotation
            }
        }
        return edges
    }
}

/**
 * Rule 2: Same-card incremental annotations must follow precedence order.
 *
 * When two annotation handlers both modify the same card via incremental entity
 * updates, the second builds on the first's output via a reverse scan. Wrong
 * order = stale entity state.
 */
data object SameCardIncrementalRule : OrderRule {
    override val name: String = "same_card_incremental"

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val cardToAnnotations = mutableMapOf<Int, MutableList<Int>>()
        for ((i, ann) in annotations.withIndex()) {
            val spec = annotationSpec(ann) ?: continue
            for (cardId in cardIdsFor(ann, spec)) {
                if (cardId != 0) {
                    cardToAnnotations.getOrPut(cardId) { mutableListOf() }.add(i)
                }
            }
        }

        val edges = mutableListOf<Pair<Int, Int>>()
        for ((_, indices) in cardToAnnotations) {
            if (indices.size < 2) continue
            for (a in indices.indices) {
                for (b in a + 1 until indices.size) {
                    val idxA = indices[a]
                    val idxB = indices[b]
                    val precA = annotationSpec(annotations[idxA])?.precedence ?: continue
                    val precB = annotationSpec(annotations[idxB])?.precedence ?: continue
                    if (precA == precB) continue
                    if (precA < precB) {
                        edges.add(idxA to idxB)
                    } else {
                        edges.add(idxB to idxA)
                    }
                }
            }
        }
        return edges
    }
}

/**
 * Rule 3: TokenCreated must precede any annotation referencing the new token's
 * instanceId as affector or affected.
 *
 * Defense-in-depth — when [leyline.game.GamePlayback.shouldSplitOnLocalTurn]
 * doesn't kick in (opponent's turn, or a non-Mobilize keyword that bundles into
 * one GSM), this rule keeps token references after their TokenCreated within a
 * single GSM. When GSM-split fires, the trigger lifecycle and combat damage
 * land in separate GSMs and this rule is a no-op. The primary mechanism for
 * ordering tokens-before-damage is the GSM-split; this enforcer is a safety
 * net.
 *
 * The token's iid only enters the client identity map when TokenCreated is
 * processed; downstream annotations (DamageDealt from a Mobilize warrior,
 * TappedUntappedPermanent, etc.) need that mapping in place or the client
 * renders the damage before the token appears on the battlefield.
 */
data object TokenCreatedFirstRule : OrderRule {
    override val name: String = "token_created_first"

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val tokenIidToTcIndex = mutableMapOf<Int, Int>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.TokenCreated in ann.typeList) {
                for (iid in ann.affectedIdsList) {
                    if (iid != 0) tokenIidToTcIndex.putIfAbsent(iid, i)
                }
            }
        }
        if (tokenIidToTcIndex.isEmpty()) return emptyList()

        val edges = mutableListOf<Pair<Int, Int>>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.TokenCreated in ann.typeList) continue
            for (refId in referencedIds(ann)) {
                val tcIndex = tokenIidToTcIndex[refId] ?: continue
                if (tcIndex == i) continue
                edges.add(tcIndex to i)
            }
        }
        return edges
    }
}

/**
 * Rule 4: PhaseOrStepModified must lead any GSM where it appears.
 *
 * Each PoSM annotation gets an edge to every non-PoSM annotation, which makes
 * the entire PoSM block lead. PoSM-vs-PoSM has no edge, so multiple PoSMs
 * preserve their input order via topological sort stability.
 */
data object PhaseOrStepFirstRule : OrderRule {
    override val name: String = "phase_or_step_first"

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val posmIndices =
            annotations.indices.filter {
                AnnotationType.PhaseOrStepModified in annotations[it].typeList
            }
        if (posmIndices.isEmpty()) return emptyList()
        val nonPosmIndices =
            annotations.indices.filter {
                AnnotationType.PhaseOrStepModified !in annotations[it].typeList
            }
        if (nonPosmIndices.isEmpty()) return emptyList()

        val edges = mutableListOf<Pair<Int, Int>>()
        for (posm in posmIndices) {
            for (other in nonPosmIndices) {
                edges.add(posm to other)
            }
        }
        return edges
    }
}

/**
 * Rule 5: Transform OIC/ZT pairs nest inside the RS/RC resolution bracket.
 *
 * Saga chapter-III + transform emits a flat sequence:
 * `[OIC{372→417}, ZT(Exile, 417), OIC{417→418}, ZT(Return, 418), RS, RC, AID]`
 *
 * The client expects the transform's identity-shuffle annotations to land
 * inside the resolution bracket so the visual sequence (start resolve →
 * transform animation → finish resolve) plays correctly. Target shape:
 * `[RS, OIC, ZT(Exile), OIC, ZT(Return), RC, AID]`.
 *
 * AID's affector carries the pre-transform source iid (the start of the OIC
 * lineage chain), set by [leyline.game.bridge.AbilityLineageRegistry]. The
 * AID's affected[0] is the ability iid — the bracket key shared with RS/RC's
 * affectorId.
 *
 * For each AID in the GSM whose affected[0] matches a same-GSM RS/RC pair:
 * 1. Walk the OIC chain starting at `srcIid = AID.affector`. Each OIC whose
 *    `orig_id` is in the chain extends it with its `new_id`.
 * 2. An OIC is in-bracket if its `new_id` is in the chain.
 * 3. A ZT is in-bracket if its `affected[0]` is in the chain, OR if it is the
 *    Resolve-category ZT for the ability iid itself.
 * 4. Emit edges so RS precedes every in-bracket annotation, every in-bracket
 *    annotation precedes RC, and AID follows RC.
 *
 * Without an AID matching an in-GSM RS, the bracket can't be identified and
 * the rule emits no edges for that bracket — cross-GSM split-resolve cases
 * (where AID lands in a follow-up GSM) need a different mechanism.
 */
data object ResolutionSandwichRule : OrderRule {
    override val name: String = "resolution_sandwich"

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val edges = mutableListOf<Pair<Int, Int>>()
        for ((aidIdx, aid) in annotations.withIndex()) {
            if (AnnotationType.AbilityInstanceDeleted !in aid.typeList) continue
            edges.addAll(bracketEdges(annotations, aidIdx, aid))
        }
        return edges
    }

    private fun bracketEdges(
        annotations: List<AnnotationInfo>,
        aidIdx: Int,
        aid: AnnotationInfo,
    ): List<Pair<Int, Int>> {
        val abilityIid = aid.affectedIdsList.firstOrNull() ?: return emptyList()
        if (abilityIid == 0) return emptyList()
        val srcIid = aid.affectorId
        if (srcIid == 0) return emptyList()

        val rsIdx =
            annotations.indexOfFirst {
                AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid
            }
        val rcIdx =
            annotations.indexOfLast {
                AnnotationType.ResolutionComplete in it.typeList && it.affectorId == abilityIid
            }
        if (rsIdx < 0 || rcIdx < 0) return emptyList()

        val chain = walkOicChain(annotations, srcIid)
        val edges = mutableListOf<Pair<Int, Int>>()
        for ((idx, ann) in annotations.withIndex()) {
            if (idx == rsIdx || idx == rcIdx || idx == aidIdx) continue
            if (isInBracket(ann, chain, abilityIid)) {
                edges.add(rsIdx to idx)
                edges.add(idx to rcIdx)
            }
        }
        // RS precedes RC defensively (covers the no-in-bracket-annotations case).
        edges.add(rsIdx to rcIdx)
        // AID trails RC.
        edges.add(rcIdx to aidIdx)
        return edges
    }

    /**
     * Walk the OIC chain starting at [seed]. Each iteration looks for an OIC
     * whose orig_id is already in the chain and adds its new_id, until no
     * more extensions are possible.
     */
    private fun walkOicChain(
        annotations: List<AnnotationInfo>,
        seed: Int,
    ): Set<Int> {
        val chain = mutableSetOf(seed)
        var added = true
        while (added) {
            added = false
            for (ann in annotations) {
                if (AnnotationType.ObjectIdChanged !in ann.typeList) continue
                val origId = ann.detailInt(DetailKeys.ORIG_ID)
                val newId = ann.detailInt(DetailKeys.NEW_ID)
                if (origId in chain && newId != 0 && newId !in chain) {
                    chain.add(newId)
                    added = true
                }
            }
        }
        return chain
    }

    private fun isInBracket(
        ann: AnnotationInfo,
        chain: Set<Int>,
        abilityIid: Int,
    ): Boolean =
        when {
            AnnotationType.ObjectIdChanged in ann.typeList ->
                ann.detailInt(DetailKeys.NEW_ID) in chain
            AnnotationType.ZoneTransfer_af5a in ann.typeList -> {
                val affected = ann.affectedIdsList.firstOrNull() ?: 0
                affected in chain || (isResolveCategory(ann) && affected == abilityIid)
            }
            else -> false
        }

    private fun isResolveCategory(ann: AnnotationInfo): Boolean =
        ann.detailsList.any { d ->
            d.key == DetailKeys.CATEGORY &&
                d.valueStringList.firstOrNull() == TransferCategory.Resolve.label
        }
}

/**
 * Rule 6: DamageDealt precedes the OIC and lethal ZT for the damaged iid.
 *
 * Within one GSM, when a creature dies from damage, the annotations describe
 * three things about the same victim: the hit (DamageDealt), the identity
 * change as it leaves the battlefield (ObjectIdChanged), and the zone move to
 * graveyard (ZoneTransfer with a lethal category). Without an explicit edge
 * the visual sequence collapses — the creature animates dying before the hit
 * registers.
 *
 * Lethal categories: SBA_Damage (state-based action damage), Destroy
 * (effect-driven destroy), SBA_LegendRule (legend rule death).
 *
 * For each DamageDealt with a victim iid, emit edges to:
 *  - any same-GSM ObjectIdChanged whose `orig_id == victim`
 *  - any same-GSM ZoneTransfer whose `affected[0] == victim` AND category is
 *    in the lethal set
 */
data object DamageBeforeDeathRule : OrderRule {
    override val name: String = "damage_before_death"

    private val lethalCategories: Set<String> =
        setOf(
            "SBA_Damage",
            TransferCategory.Destroy.label,
            TransferCategory.SbaLegendRule.label,
        )

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val edges = mutableListOf<Pair<Int, Int>>()
        for ((dmgIdx, dmg) in annotations.withIndex()) {
            if (AnnotationType.DamageDealt_af5a !in dmg.typeList) continue
            val victimIid = dmg.affectedIdsList.firstOrNull() ?: continue
            if (victimIid == 0) continue
            for ((idx, ann) in annotations.withIndex()) {
                if (idx == dmgIdx) continue
                val isOicForVictim =
                    AnnotationType.ObjectIdChanged in ann.typeList &&
                        ann.detailInt(DetailKeys.ORIG_ID) == victimIid
                val isLethalZtForVictim =
                    AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                        ann.affectedIdsList.firstOrNull() == victimIid &&
                        isLethalCategory(ann)
                if (isOicForVictim || isLethalZtForVictim) {
                    edges.add(dmgIdx to idx)
                }
            }
        }
        return edges
    }

    private fun isLethalCategory(zt: AnnotationInfo): Boolean {
        val category =
            zt.detailsList
                .firstOrNull { it.key == DetailKeys.CATEGORY }
                ?.valueStringList
                ?.firstOrNull() ?: return false
        return category in lethalCategories
    }
}

/**
 * Rule 7: Combat-damage step GSMs follow a precedence ladder.
 *
 * Trigger: a PhaseOrStepModified with phase=Combat (3) AND step=CombatDamage
 * (7) is present in the GSM.
 *
 * Ladder (annotations not present don't get edges):
 *
 * ```
 * DamageDealt → DamagedThisTurn → SyntheticEvent → ModifiedLife
 *   → LayeredEffectDestroyed → ObjectIdChanged → ZoneTransfer
 *   → AbilityInstanceDeleted
 * ```
 *
 * Implementation is quadratic over the 8-type ladder: for every pair (i, j)
 * with j > i, emit edges from every instance of `ladder[i]` to every instance
 * of `ladder[j]` if both types are present. This is gap-tolerant — missing
 * intermediate types don't break the chain because the topo sort is fed direct
 * edges between every present pair.
 *
 * PhaseOrStepFirstRule already pulls PoSM to the front; this rule starts at
 * DamageDealt and lets PhaseFirst handle the lead.
 */
data object CombatDamageBlockRule : OrderRule {
    override val name: String = "combat_damage_block"

    private val ladder: List<AnnotationType> =
        listOf(
            AnnotationType.DamageDealt_af5a,
            AnnotationType.DamagedThisTurn,
            AnnotationType.SyntheticEvent,
            AnnotationType.ModifiedLife,
            AnnotationType.LayeredEffectDestroyed,
            AnnotationType.ObjectIdChanged,
            AnnotationType.ZoneTransfer_af5a,
            AnnotationType.AbilityInstanceDeleted,
        )

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        if (!isCombatDamageGsm(annotations)) return emptyList()
        val byType: Map<AnnotationType, List<Int>> =
            ladder.associateWith { type ->
                annotations.indices.filter { type in annotations[it].typeList }
            }
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in ladder.indices) {
            val earlier = byType[ladder[i]] ?: continue
            if (earlier.isEmpty()) continue
            for (j in i + 1 until ladder.size) {
                val later = byType[ladder[j]] ?: continue
                if (later.isEmpty()) continue
                for (e in earlier) {
                    for (l in later) {
                        if (e != l) edges.add(e to l)
                    }
                }
            }
        }
        return edges
    }

    private fun isCombatDamageGsm(annotations: List<AnnotationInfo>): Boolean =
        annotations.any { ann ->
            AnnotationType.PhaseOrStepModified in ann.typeList &&
                ann.detailInt(DetailKeys.PHASE) == COMBAT_PHASE &&
                ann.detailInt(DetailKeys.STEP) == COMBAT_DAMAGE_STEP
        }

    /** Phase.Combat_a549 enum value (3). */
    private const val COMBAT_PHASE: Int = 3

    /** Step.CombatDamage_a2cb enum value (7). */
    private const val COMBAT_DAMAGE_STEP: Int = 7
}

// ---- Helpers shared across rules ----------------------------------------

/**
 * Incremental entity annotation metadata for same-card chaining
 * ([SameCardIncrementalRule]).
 *
 * Lower precedence = must come first when two types affect the same card.
 * [cardIdFromAffected] = true if the card ID is in affectedIds (most types),
 * false if it's in affectorId (LayeredEffectCreated, AttachmentCreated).
 */
internal data class IncrementalSpec(
    val precedence: Int,
    val cardIdFromAffected: Boolean = true,
)

/**
 * Precedence table:
 * - LayeredEffectCreated BEFORE AttachmentCreated
 * - ControllerChanged BEFORE TappedUntapped
 *
 * Validated by puzzle fixtures under `puzzles/` exercising these orderings.
 */
internal val INCREMENTAL_SPECS: Map<AnnotationType, IncrementalSpec> =
    mapOf(
        AnnotationType.ControllerChanged to IncrementalSpec(0),
        AnnotationType.TappedUntappedPermanent to IncrementalSpec(1),
        AnnotationType.DamageDealt_af5a to IncrementalSpec(2),
        AnnotationType.CounterAdded to IncrementalSpec(3),
        AnnotationType.CounterRemoved to IncrementalSpec(3),
        AnnotationType.PowerToughnessModCreated to IncrementalSpec(4),
        AnnotationType.LayeredEffectCreated to IncrementalSpec(5, cardIdFromAffected = false),
        AnnotationType.AttachmentCreated to IncrementalSpec(6, cardIdFromAffected = false),
    )

/** Get the incremental spec for an annotation, or null if not applicable. */
internal fun annotationSpec(ann: AnnotationInfo): IncrementalSpec? = ann.typeList.firstNotNullOfOrNull { INCREMENTAL_SPECS[it] }

/** Extract card IDs from an annotation based on its spec. */
internal fun cardIdsFor(
    ann: AnnotationInfo,
    spec: IncrementalSpec,
): List<Int> =
    if (spec.cardIdFromAffected) {
        ann.affectedIdsList.toList()
    } else {
        if (ann.affectorId != 0) listOf(ann.affectorId) else emptyList()
    }

/** All IDs referenced by an annotation (affectedIds + affectorId). */
internal fun referencedIds(ann: AnnotationInfo): Set<Int> =
    buildSet {
        addAll(ann.affectedIdsList)
        if (ann.affectorId != 0) add(ann.affectorId)
    }

/** Extract int32 detail value by key, returns 0 if not found. */
internal fun AnnotationInfo.detailInt(key: String): Int =
    detailsList.firstOrNull { it.key == key }?.let {
        if (it.valueInt32Count > 0) it.getValueInt32(0) else 0
    } ?: 0
