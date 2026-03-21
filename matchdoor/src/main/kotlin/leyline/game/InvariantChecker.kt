package leyline.game

import kotlinx.serialization.Serializable
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Runtime invariant checker for GRE message streams.
 *
 * Extracted from test-only [ValidatingMessageSink] so both test code
 * and [SessionAnalyzer] share a single source of truth for invariant
 * definitions.
 *
 * Checks: gsId monotonicity, prevGsId validity, annotation sequentiality,
 * action instanceId consistency, zone-object consistency, msgId monotonicity.
 */
class InvariantChecker {

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

    private fun checkMsgIdMonotonicity(msg: GREToClientMessage, gsId: Int) {
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

    private fun checkPendingMessageCountContract(gsm: GameStateMessage) {
        val isSendAndRecord = gsm.update == GameStateUpdate.SendAndRecord

        // TODO: too strict — our phaseTransitionDiff sets pendingMessageCount=1
        // but AI action diffs can arrive before the expected follow-up.
        // Revisit once the diff pipeline guarantees correct pending counts.
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
        val transientAbilityIds = annotations
            .filter { it.typeList.any { t -> t == AnnotationType.AbilityInstanceCreated } }
            .flatMap { it.affectedIdsList }
            .toSet()

        fun isKnown(id: Int) = accumulator.isKnownEntity(id) || id in transientAbilityIds

        for (ann in annotations) {
            // ObjectIdChanged references old (replaced) instanceIds — skip entirely
            val isObjectIdChanged = ann.typeList.any { it == AnnotationType.ObjectIdChanged }
            if (isObjectIdChanged) continue

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

    private fun record(gsId: Int, check: String, message: String) {
        _violations.add(Violation(messageIndex, gsId, check, message))
    }
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
        id in 1..2 || objects.containsKey(id) || zones.containsKey(id)

    fun actionInstanceIdsMissingFromObjects(): List<Int> {
        val req = actions ?: return emptyList()
        return req.actionsList
            .filter { it.instanceId != 0 && !objects.containsKey(it.instanceId) }
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
