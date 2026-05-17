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
            ResolveTransferAfterResolutionCompleteRule,
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
 * Rule 5: Resolve-category zone transfers follow the RS/RC pair.
 *
 * The client-facing resolve shape puts ResolutionStart/ResolutionComplete first,
 * then applies the resolving object's zone movement. Each Resolve transfer gets
 * RS -> RC -> ZT edges.
 */
data object ResolveTransferAfterResolutionCompleteRule : OrderRule {
    override val name: String = "resolve_transfer_after_resolution_complete"

    override fun edges(annotations: List<AnnotationInfo>): List<Pair<Int, Int>> {
        val rs = annotations.indexOfFirst { AnnotationType.ResolutionStart in it.typeList }
        val rc = annotations.indexOfLast { AnnotationType.ResolutionComplete in it.typeList }
        if (rs < 0 || rc < 0) return emptyList()

        val edges = mutableListOf<Pair<Int, Int>>()
        for ((i, ann) in annotations.withIndex()) {
            if (AnnotationType.ZoneTransfer_af5a !in ann.typeList) continue
            if (ann.detailString(DetailKeys.CATEGORY) != TransferCategory.Resolve.label) continue
            edges.add(rs to rc)
            edges.add(rc to i)
        }
        return edges
    }
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

/** Extract string detail value by key, returns empty string if not found. */
internal fun AnnotationInfo.detailString(key: String): String =
    detailsList
        .firstOrNull { it.key == key }
        ?.let {
            if (it.valueStringCount > 0) it.getValueString(0) else null
        }.orEmpty()
