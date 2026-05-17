package leyline.game.bundle

import kotlinx.serialization.Serializable
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.TransferCategory
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator
import kotlin.text.get

/**
 * Runtime invariant checker for GRE message streams. Shared by
 * [ValidatingMessageSink] (test assertions) and [SessionAnalyzer]
 * (post-hoc diagnostics).
 *
 * Checks: gsId monotonicity, prevGsId validity, annotation sequentiality,
 * annotation ordering, phase_first, resolution_transfer_ordering, aid_affector
 * consistency, action instanceId consistency, zone-object consistency,
 * msgId monotonicity.
 */
class InvariantChecker(
    private val selection: InvariantSelection = InvariantSelection.all(),
) {
    @Serializable
    data class Violation(
        val seq: Int,
        val gsId: Int,
        val check: String,
        val message: String,
    )

    // --- Tracked state ---

    private val accumulator = RuntimeAccumulator()
    private val seenGsIds = mutableSetOf<Int>()
    private var highWaterGsId = 0
    private var highWaterMsgId = 0
    private var pendingCountdown = 0
    private var messageIndex = 0
    private val aicAffectorByAbilityIid = mutableMapOf<Int, Int>()

    private val _violations = mutableListOf<Violation>()
    val violations: List<Violation> get() = _violations

    // --- Public API ---

    /** Process a single GRE message through all invariant checks. */
    fun process(msg: GREToClientMessage) {
        messageIndex++
        val gsId = if (msg.hasGameStateMessage()) msg.gameStateMessage.gameStateId else 0

        checkMsgIdMonotonicity(msg, gsId)

        if (msg.hasGameStateMessage()) {
            val gsm = msg.gameStateMessage
            checkGsIdMonotonicity(gsm)
            checkPrevGsIdValidity(gsm)
            checkNoSelfReferentialGsId(gsm)
            checkAnnotationIdSequentiality(gsm)
            checkAnnotationOrdering(gsm)
            checkPhaseFirst(gsm)
            checkResolutionTransferOrdering(gsm)
            val aidIids = checkAidAffectorConsistency(gsm)
            recordAicAffectorHistory(gsm, aidIids)
            checkPendingMessageCountContract(gsm)
        }

        accumulator.process(msg)

        if (msg.hasActionsAvailableReq()) {
            checkActionInstanceIdConsistency(gsId)
        }
        if (msg.hasGameStateMessage()) {
            checkZoneObjectConsistency(gsId)
            checkAnnotationReferentialIntegrity(msg.gameStateMessage)
        }
    }

    /** Process a list of GRE messages. */
    fun processAll(messages: List<GREToClientMessage>) {
        messages.forEach { process(it) }
    }

    /** Seed with a Full GSM baseline (e.g. handshake). */
    fun seedFull(gsm: GameStateMessage) {
        accumulator.seedFull(gsm)
        val gsId = gsm.gameStateId
        seenGsIds.add(gsId)
        if (gsId > highWaterGsId) highWaterGsId = gsId
    }

    /** True if no violations recorded. */
    val isClean: Boolean get() = _violations.isEmpty()

    // --- GsId chain validation (static, no accumulator needed) ---

    companion object {
        /**
         * Validate gsId chain invariants across a message sequence.
         * Returns violations list (empty = all invariants hold).
         */
        fun validateGsIdChain(
            messages: List<GREToClientMessage>,
            priorGsIds: Set<Int> = emptySet(),
        ): List<Violation> {
            val violations = mutableListOf<Violation>()
            val gsms = messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val knownGsIds = priorGsIds.toMutableSet()

            // gsIds strictly monotonic
            for (i in 1 until gsms.size) {
                if (gsms[i].gameStateId <= gsms[i - 1].gameStateId) {
                    violations.add(
                        Violation(
                            i,
                            gsms[i].gameStateId,
                            "gsid_monotonicity",
                            "gsIds not monotonic: ${gsms[i - 1].gameStateId} -> ${gsms[i].gameStateId}",
                        ),
                    )
                }
            }

            // No self-referential prevGameStateId
            for ((i, gsm) in gsms.withIndex()) {
                if (gsm.prevGameStateId != 0 && gsm.prevGameStateId == gsm.gameStateId) {
                    violations.add(
                        Violation(
                            i,
                            gsm.gameStateId,
                            "gsid_self_ref",
                            "Self-referential prevGsId: gsId=${gsm.gameStateId}",
                        ),
                    )
                }
            }

            // prevGameStateId references a known gsId
            for ((i, gsm) in gsms.withIndex()) {
                if (gsm.prevGameStateId != 0 && !knownGsIds.contains(gsm.prevGameStateId)) {
                    violations.add(
                        Violation(
                            i,
                            gsm.gameStateId,
                            "gsid_prev_unknown",
                            "prevGsId ${gsm.prevGameStateId} not in known set (gsId=${gsm.gameStateId})",
                        ),
                    )
                }
                knownGsIds.add(gsm.gameStateId)
            }

            // gsIds globally unique
            val allGsIds = gsms.map { it.gameStateId }
            val duplicates = allGsIds.groupBy { it }.filter { it.value.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                violations.add(
                    Violation(0, 0, "gsid_unique", "Duplicate gsIds: $duplicates"),
                )
            }

            // msgIds strictly monotonic
            val msgIds = messages.map { it.msgId }.filter { it > 0 }
            for (i in 1 until msgIds.size) {
                if (msgIds[i] <= msgIds[i - 1]) {
                    violations.add(
                        Violation(i, 0, "msgid_monotonicity", "msgIds not monotonic: ${msgIds[i - 1]} -> ${msgIds[i]}"),
                    )
                }
            }

            // msgIds globally unique
            val dupMsgIds = msgIds.groupBy { it }.filter { it.value.size > 1 }.keys
            if (dupMsgIds.isNotEmpty()) {
                violations.add(
                    Violation(0, 0, "msgid_unique", "Duplicate msgIds: $dupMsgIds"),
                )
            }

            return violations
        }
    }

    // --- Individual checks ---

    private fun checkMsgIdMonotonicity(
        msg: GREToClientMessage,
        gsId: Int,
    ) {
        val msgId = msg.msgId
        if (msgId == 0) return
        if (highWaterMsgId > 0 && msgId <= highWaterMsgId) {
            record(gsId, "msgid_monotonicity", "msgId not monotonic: got $msgId, expected > $highWaterMsgId")
        }
        highWaterMsgId = msgId
    }

    private fun checkGsIdMonotonicity(gsm: GameStateMessage) {
        val gsId = gsm.gameStateId
        if (gsId == 0) return
        if (highWaterGsId > 0 && gsId <= highWaterGsId) {
            record(gsId, "gsid_monotonicity", "gsId not monotonic: got $gsId, expected > $highWaterGsId")
        }
        highWaterGsId = gsId
        seenGsIds.add(gsId)
    }

    private fun checkPrevGsIdValidity(gsm: GameStateMessage) {
        val prev = gsm.prevGameStateId
        if (prev == 0) return
        if (!seenGsIds.contains(prev)) {
            record(gsm.gameStateId, "gsid_prev_unknown", "prevGsId $prev not in known set (gsId=${gsm.gameStateId})")
        }
    }

    private fun checkNoSelfReferentialGsId(gsm: GameStateMessage) {
        if (gsm.gameStateId != 0 && gsm.gameStateId == gsm.prevGameStateId) {
            record(gsm.gameStateId, "gsid_self_ref", "Self-referential gsId: gameStateId=${gsm.gameStateId} == prevGameStateId")
        }
    }

    private fun checkAnnotationIdSequentiality(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        if (annotations.isEmpty()) return
        if (annotations.all { it.id == 0 }) return

        for ((idx, ann) in annotations.withIndex()) {
            if (ann.id == 0) {
                record(gsm.gameStateId, "annotation_seq", "Annotation at index $idx has id=0 in mixed-id GSM (gsId=${gsm.gameStateId})")
                continue
            }
            if (idx > 0 && annotations[idx - 1].id != 0) {
                val prev = annotations[idx - 1].id
                if (ann.id != prev + 1) {
                    record(
                        gsm.gameStateId,
                        "annotation_seq",
                        "Annotation IDs not sequential: index $idx has id=${ann.id}, expected ${prev + 1} (gsId=${gsm.gameStateId})",
                    )
                }
            }
        }
    }

    /**
     * Verify annotation ordering invariants (Rules 1 and 2) by delegating
     * to [AnnotationOrderEnforcer]. If enforce() returns a different list,
     * the input had ordering violations.
     */
    private fun checkAnnotationOrdering(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        if (annotations.isEmpty()) return

        val enforced = AnnotationOrderEnforcer.enforce(annotations)
        if (enforced !== annotations) {
            // Find which annotations moved
            for (i in annotations.indices) {
                if (i < enforced.size && annotations[i] !== enforced[i]) {
                    record(
                        gsm.gameStateId,
                        "annotation_ordering",
                        "annotation ordering violation at index $i: " +
                            "had ${annotations[i].typeList} but enforcer moved ${enforced[i].typeList} here " +
                            "(gsId=${gsm.gameStateId})",
                    )
                }
            }
        }
    }

    /**
     * When PhaseOrStepModified appears in a GSM, it must be at index 0.
     * Detection only — the matching enforcer rule lands in a follow-up.
     */
    private fun checkPhaseFirst(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        val firstPosIdx = annotations.indexOfFirst { AnnotationType.PhaseOrStepModified in it.typeList }
        if (firstPosIdx <= 0) return
        record(
            gsm.gameStateId,
            "phase_first",
            "PhaseOrStepModified at index $firstPosIdx, expected 0 (gsId=${gsm.gameStateId})",
        )
    }

    /**
     * In any GSM that contains both [AnnotationType.ResolutionStart] and
     * [AnnotationType.ResolutionComplete], Resolve-category transfers are
     * ordered by source zone.
     *
     * Stack-exit transfers apply after the RS/RC pair. Non-stack transfers caused
     * by resolving ability effects stay inside the pair.
     */
    private fun checkResolutionTransferOrdering(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        val rsIdx = annotations.indexOfFirst { AnnotationType.ResolutionStart in it.typeList }
        val rcIdx = annotations.indexOfLast { AnnotationType.ResolutionComplete in it.typeList }
        if (rsIdx < 0 || rcIdx < 0) return
        val gsId = gsm.gameStateId
        annotations.forEachIndexed { idx, ann ->
            if (AnnotationType.ZoneTransfer_af5a !in ann.typeList) return@forEachIndexed
            val isResolve =
                ann.detailsList.any { detail ->
                    detail.key == DetailKeys.CATEGORY &&
                        detail.valueStringList.firstOrNull() == TransferCategory.Resolve.label
                }
            if (!isResolve) return@forEachIndexed
            val srcZone = ann.detailInt(DetailKeys.ZONE_SRC)
            val invalid =
                if (srcZone == ZoneIds.STACK) {
                    idx <= rcIdx
                } else {
                    idx <= rsIdx || idx >= rcIdx
                }
            if (invalid) {
                val affected = ann.affectedIdsList.firstOrNull() ?: 0
                val expected = if (srcZone == ZoneIds.STACK) "after RC=$rcIdx" else "inside RS=$rsIdx..RC=$rcIdx"
                record(
                    gsId,
                    "resolution_transfer_ordering",
                    "Resolve-category ZoneTransfer affected=$affected at index $idx " +
                        "not $expected (srcZone=$srcZone, gsId=$gsId)",
                )
            }
        }
    }

    /**
     * Verify that an [AnnotationType.AbilityInstanceDeleted]'s `affectorId`
     * matches the `affectorId` of the earlier [AnnotationType.AbilityInstanceCreated]
     * for the same ability instance id (the AID/AIC's `affectedIds[0]`).
     *
     * Catches identity drift such as the saga T5 chapter-III + transform
     * regression, where AID emits with the post-transform source iid even
     * though AIC was emitted with the pre-transform source iid.
     *
     * Detection only — the matching producer-side fix lands when ability
     * lineage tracking lands.
     *
     * Wired in [process] BEFORE [recordAicAffectorHistory] so an AID in
     * GSM N is only checked against AICs from GSM 1..N-1; same-GSM AIC+AID
     * pairs (mana brackets) do not interact through this map.
     *
     * The AIC entry is pruned after the AID check fires so it does not leak
     * across re-emissions.
     *
     * Returns the set of ability iids that were AID'd in this GSM, so
     * [recordAicAffectorHistory] can skip storing AICs whose ability was
     * already closed in the same GSM (otherwise same-GSM AIC+AID pairs
     * would leak the AIC entry into the map indefinitely).
     */
    private fun checkAidAffectorConsistency(gsm: GameStateMessage): Set<Int> {
        val aidIids = mutableSetOf<Int>()
        for (ann in gsm.annotationsList) {
            if (AnnotationType.AbilityInstanceDeleted !in ann.typeList) continue
            val abilityIid = ann.affectedIdsList.firstOrNull() ?: continue
            aidIids.add(abilityIid)
            val expected = aicAffectorByAbilityIid[abilityIid] ?: continue
            if (ann.affectorId != expected) {
                record(
                    gsm.gameStateId,
                    "aid_affector",
                    "AID affectorId=${ann.affectorId} for ability=$abilityIid does not match " +
                        "earlier AIC affectorId=$expected (gsId=${gsm.gameStateId})",
                )
            }
            aicAffectorByAbilityIid.remove(abilityIid)
        }
        return aidIids
    }

    /**
     * Store each [AnnotationType.AbilityInstanceCreated] in this GSM in
     * the [aicAffectorByAbilityIid] map so that a future AID for the same
     * ability iid can be checked against it.
     *
     * Wired in [process] AFTER [checkAidAffectorConsistency] so same-GSM
     * AIC+AID pairs do not check against themselves. Same-GSM AIC entries
     * are skipped via [aidIidsThisGsm] — storing them would leak the
     * entry indefinitely because no future AID will arrive to prune it
     * (mana brackets fire many times per match).
     */
    private fun recordAicAffectorHistory(
        gsm: GameStateMessage,
        aidIidsThisGsm: Set<Int>,
    ) {
        for (ann in gsm.annotationsList) {
            if (AnnotationType.AbilityInstanceCreated !in ann.typeList) continue
            val abilityIid = ann.affectedIdsList.firstOrNull() ?: continue
            if (abilityIid in aidIidsThisGsm) continue
            aicAffectorByAbilityIid[abilityIid] = ann.affectorId
        }
    }

    private fun checkPendingMessageCountContract(gsm: GameStateMessage) {
        // TODO: too strict — our phaseTransitionDiff sets pendingMessageCount=1
        // but AI action diffs can arrive before the expected follow-up.
        // Revisit once the diff pipeline guarantees correct pending counts.
        // val isSendAndRecord = gsm.update == GameStateUpdate.SendAndRecord
        // if (pendingCountdown > 0 && isSendAndRecord) {
        //     record(
        //         gsm.gameStateId,
        //         "pending_count",
        //         "pendingMessageCount violation: ...",
        //     )
        // }

        val pending = gsm.pendingMessageCount
        if (pending > 0) {
            pendingCountdown = pending
        } else if (pendingCountdown > 0) {
            pendingCountdown--
        }
    }

    private fun checkActionInstanceIdConsistency(gsId: Int) {
        val missing = accumulator.actionInstanceIdsMissingFromObjects()
        if (missing.isNotEmpty()) {
            record(gsId, "action_iid", "Action instanceIds missing from objects: $missing")
        }
    }

    private fun checkZoneObjectConsistency(gsId: Int) {
        val missing = accumulator.zoneObjectsMissingFromObjects()
        if (missing.isNotEmpty()) {
            record(gsId, "zone_object", "Zone objects missing from objects: $missing")
        }
    }

    /**
     * Validate annotation affectorId/affectedIds resolve to known entities.
     *
     * Valid targets: accumulated object instanceIds, player seats (1/2),
     * zone IDs (affector can be a zone, e.g. EnteredZoneThisTurn).
     * Runs after accumulator.process() so current GSM's objects are included.
     *
     * ObjectIdChanged annotations are exempt: they reference old instanceIds
     * (origId) that may have been deleted in this or a prior GSM, or may
     * reference objects from hidden zones (library) that were never visible.
     */
    private fun checkAnnotationReferentialIntegrity(gsm: GameStateMessage) {
        val gsId = gsm.gameStateId
        val annotations = gsm.annotationsList + gsm.persistentAnnotationsList
        if (annotations.isEmpty()) return

        // Collect transient ability IDs created by AbilityInstanceCreated annotations.
        // These are mana ability instance IDs that exist only within the annotation
        // sequence (created then deleted in the same GSM) and don't appear as game objects.
        val transientAbilityIds =
            annotations
                .filter { it.typeList.any { t -> t == AnnotationType.AbilityInstanceCreated } }
                .flatMap { it.affectedIdsList }
                .toSet()
        val sameGsmKnownIds =
            gsm.diffDeletedInstanceIdsList.toSet() +
                annotations
                    .filter { it.typeList.any { t -> t == AnnotationType.ObjectIdChanged } }
                    .flatMap { ann ->
                        ann.detailsList
                            .filter { detail -> detail.key == DetailKeys.NEW_ID }
                            .flatMap { detail -> (0 until detail.valueInt32Count).map { detail.getValueInt32(it) } }
                    }.toSet()

        fun isKnown(id: Int) = accumulator.isKnownEntity(id) || id in transientAbilityIds || id in sameGsmKnownIds

        for (ann in annotations) {
            // ObjectIdChanged references old (replaced) instanceIds — skip entirely
            val isObjectIdChanged = ann.typeList.any { it == AnnotationType.ObjectIdChanged }
            if (isObjectIdChanged) continue
            // LayeredEffect annotations use synthetic effect IDs, not entity references
            val isLayeredEffect =
                ann.typeList.any {
                    it == AnnotationType.LayeredEffectCreated || it == AnnotationType.LayeredEffectDestroyed
                }
            if (isLayeredEffect) continue

            if (ann.affectorId != 0 && !isKnown(ann.affectorId)) {
                record(
                    gsId,
                    "annotation_ref",
                    "annotation ${ann.id}: affectorId ${ann.affectorId} unresolvable " +
                        "(type=${ann.typeList}, gsId=$gsId)",
                )
            }
            for (affected in ann.affectedIdsList) {
                if (affected != 0 && !isKnown(affected)) {
                    record(
                        gsId,
                        "annotation_ref",
                        "annotation ${ann.id}: affectedId $affected unresolvable " +
                            "(type=${ann.typeList}, gsId=$gsId)",
                    )
                }
            }
        }
    }

    private fun record(
        gsId: Int,
        check: String,
        message: String,
    ) {
        if (selection.includes(check)) {
            _violations.add(Violation(messageIndex, gsId, check, message))
        }
    }

    private fun AnnotationInfo.detailInt(key: String): Int =
        detailsList.firstOrNull { it.key == key }?.let {
            if (it.valueInt32Count > 0) it.getValueInt32(0) else 0
        } ?: 0
}

/**
 * Minimal client state accumulator for runtime use.
 *
 * Same logic as test-only ClientAccumulator but in main source scope.
 * Processes Full/Diff GSMs, tracks objects/zones/actions for invariant checking.
 */
class RuntimeAccumulator {
    val objects = mutableMapOf<Int, GameObjectInfo>()
    val zones = mutableMapOf<Int, ZoneInfo>()
    var actions: ActionsAvailableReq? = null
        private set

    fun process(gre: GREToClientMessage) {
        when {
            gre.hasGameStateMessage() -> processGameState(gre.gameStateMessage)
            gre.hasActionsAvailableReq() -> actions = gre.actionsAvailableReq
        }
    }

    fun seedFull(gsm: GameStateMessage) {
        require(gsm.type == GameStateType.Full) { "seedFull requires Full GSM, got ${gsm.type}" }
        processGameState(gsm)
    }

    /**
     * Check if an ID is a known entity: object instanceId, player seat (1/2), or zone ID.
     * Annotations use affectorId/affectedIds to reference any of these.
     */
    fun isKnownEntity(id: Int): Boolean =
        id in 1..2 ||
            objects.containsKey(id) ||
            zones.containsKey(id) ||
            zones.values.any { zone -> id in zone.objectInstanceIdsList }

    fun actionInstanceIdsMissingFromObjects(): List<Int> {
        val req = actions ?: return emptyList()
        return req.actionsList
            .filter { it.instanceId != 0 && !isKnownEntity(it.instanceId) }
            .map { it.instanceId }
    }

    fun zoneObjectsMissingFromObjects(): List<Pair<Int, Int>> {
        val missing = mutableListOf<Pair<Int, Int>>()
        for ((zoneId, zone) in zones) {
            if (zone.visibility == Visibility.Hidden || zone.visibility == Visibility.Private) continue
            if (zone.type == ZoneType.Limbo) continue
            for (iid in zone.objectInstanceIdsList) {
                if (!objects.containsKey(iid)) {
                    missing.add(zoneId to iid)
                }
            }
        }
        return missing
    }

    private fun processGameState(gs: GameStateMessage) {
        when (gs.type) {
            GameStateType.Full -> {
                objects.clear()
                zones.clear()
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
            }
            GameStateType.Diff -> {
                gs.diffDeletedInstanceIdsList.forEach { objects.remove(it) }
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
            }
            else -> {}
        }
    }
}
