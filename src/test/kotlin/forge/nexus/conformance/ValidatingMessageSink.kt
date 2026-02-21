package forge.nexus.conformance

import forge.nexus.server.ListMessageSink
import forge.nexus.server.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * [MessageSink] decorator that wraps [ListMessageSink] and runs invariant checks
 * on every [send] call automatically.
 *
 * Swap in for any test that uses [ListMessageSink] to get free invariant coverage
 * without changing assertions. Access captured messages via [inner].
 *
 * @param inner   the underlying [ListMessageSink] (delegates storage)
 * @param strict  when true, throws [AssertionError] on first violation;
 *                when false, logs violations into [violations] for later inspection
 */
class ValidatingMessageSink(
    val inner: ListMessageSink = ListMessageSink(),
    private val strict: Boolean = true,
) : MessageSink {

    // --- Tracked state ---

    private val accumulator = ClientAccumulator()
    private val seenGsIds = mutableSetOf<Int>()
    private var highWaterGsId = 0
    private var highWaterMsgId = 0

    /** Countdown for [pendingMessageCount] contract. */
    private var pendingCountdown = 0

    /** Accumulated violation descriptions (useful when [strict] = false). */
    val violations = mutableListOf<String>()

    // --- MessageSink ---

    override fun send(messages: List<GREToClientMessage>) {
        for (msg in messages) {
            validateMessage(msg)
        }
        inner.send(messages)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        inner.sendRaw(msg)
    }

    // --- Public API ---

    /** Convenience: delegate to [inner] messages list. */
    val messages: MutableList<GREToClientMessage> get() = inner.messages
    val rawMessages: MutableList<MatchServiceToClientMessage> get() = inner.rawMessages

    /** Assert no violations accumulated (for use in @AfterMethod). */
    fun assertClean() {
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "ValidatingMessageSink recorded ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    fun clear() {
        inner.clear()
    }

    // --- Seed support ---

    /**
     * Seed the internal accumulator with a Full GSM (e.g. handshake baseline).
     * Must be called before processing thin Diffs so zone/object state is populated.
     */
    fun seedFull(gsm: GameStateMessage) {
        accumulator.seedFull(gsm)
        val gsId = gsm.gameStateId
        seenGsIds.add(gsId)
        if (gsId > highWaterGsId) highWaterGsId = gsId
    }

    // --- Invariant checks ---

    private fun validateMessage(msg: GREToClientMessage) {
        checkMsgIdMonotonicity(msg)

        if (msg.hasGameStateMessage()) {
            val gsm = msg.gameStateMessage
            checkGsIdMonotonicity(gsm)
            checkPrevGsIdValidity(gsm)
            checkNoSelfReferentialGsId(gsm)
            checkAnnotationIdSequentiality(gsm)
            checkPendingMessageCountContract(gsm)
        }

        // Feed message through accumulator, then check post-conditions
        accumulator.process(msg)

        if (msg.hasActionsAvailableReq()) {
            checkActionInstanceIdConsistency()
        }

        if (msg.hasGameStateMessage()) {
            checkZoneObjectConsistency()
        }
    }

    /** msgId must be strictly increasing across all messages. */
    private fun checkMsgIdMonotonicity(msg: GREToClientMessage) {
        val msgId = msg.msgId
        if (msgId == 0) return // some messages (e.g. QueuedGameState) may not carry msgId
        if (highWaterMsgId > 0 && msgId <= highWaterMsgId) {
            record("msgId not monotonic: got $msgId, expected > $highWaterMsgId")
        }
        highWaterMsgId = msgId
    }

    /** gsId must be strictly greater than the last seen gsId. */
    private fun checkGsIdMonotonicity(gsm: GameStateMessage) {
        val gsId = gsm.gameStateId
        if (gsId == 0) return
        if (highWaterGsId > 0 && gsId <= highWaterGsId) {
            record("gsId not monotonic: got $gsId, expected > $highWaterGsId")
        }
        highWaterGsId = gsId
        seenGsIds.add(gsId)
    }

    /** prevGameStateId must reference a gsId we've already seen. */
    private fun checkPrevGsIdValidity(gsm: GameStateMessage) {
        val prev = gsm.prevGameStateId
        if (prev == 0) return // not set
        if (!seenGsIds.contains(prev)) {
            record("prevGsId $prev not in known set $seenGsIds (gsId=${gsm.gameStateId})")
        }
    }

    /** gameStateId != prevGameStateId. */
    private fun checkNoSelfReferentialGsId(gsm: GameStateMessage) {
        if (gsm.gameStateId != 0 && gsm.gameStateId == gsm.prevGameStateId) {
            record("Self-referential gsId: gameStateId=${gsm.gameStateId} == prevGameStateId")
        }
    }

    /**
     * Within a single GSM, annotation IDs must be sequential (each = prev + 1)
     * and non-zero — when IDs are assigned.
     *
     * The production code uses a global counter that doesn't reset per-GSM, so
     * IDs may start at any value (e.g. 50), but must be contiguous within one GSM.
     *
     * Some GSMs (e.g. initial Full state from MatchSession handshake) carry
     * annotations without assigned IDs (all zeros). Skip the check in that case.
     */
    private fun checkAnnotationIdSequentiality(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        if (annotations.isEmpty()) return

        // If no annotations have IDs assigned, skip (IDs not used for this GSM)
        if (annotations.all { it.id == 0 }) return

        for ((idx, ann) in annotations.withIndex()) {
            if (ann.id == 0) {
                record("Annotation at index $idx has id=0 in mixed-id GSM (gsId=${gsm.gameStateId})")
                continue
            }
            if (idx > 0 && annotations[idx - 1].id != 0) {
                val prev = annotations[idx - 1].id
                if (ann.id != prev + 1) {
                    record(
                        "Annotation IDs not sequential: index $idx has id=${ann.id}, " +
                            "expected ${prev + 1} (gsId=${gsm.gameStateId})",
                    )
                }
            }
        }
    }

    /**
     * pendingMessageCount contract: if a GSM has pendingMessageCount=N,
     * exactly N more messages must arrive before the next GSM with
     * gameStateUpdate=SendAndRecord.
     *
     * Implemented as soft warning initially (logs, doesn't throw even in strict mode).
     */
    private fun checkPendingMessageCountContract(gsm: GameStateMessage) {
        val isSendAndRecord = gsm.update == GameStateUpdate.SendAndRecord

        if (pendingCountdown > 0 && isSendAndRecord) {
            // A new SendAndRecord arrived before countdown reached zero
            val msg = "pendingMessageCount violation: countdown was $pendingCountdown " +
                "when SendAndRecord arrived (gsId=${gsm.gameStateId})"
            violations.add(msg) // soft warning — don't throw even in strict mode
        }

        val pending = gsm.pendingMessageCount
        if (pending > 0) {
            pendingCountdown = pending
        } else if (pendingCountdown > 0) {
            pendingCountdown--
        }
    }

    /**
     * Every instanceId in actionsAvailableReq must exist in the accumulator's
     * current objects map.
     */
    private fun checkActionInstanceIdConsistency() {
        val missing = accumulator.actionInstanceIdsMissingFromObjects()
        if (missing.isNotEmpty()) {
            record("Action instanceIds missing from objects: $missing")
        }
    }

    /**
     * After processing each message through the accumulator, visible zone object
     * references must all exist in objects (skip Hidden/Private/Limbo).
     */
    private fun checkZoneObjectConsistency() {
        val missing = accumulator.zoneObjectsMissingFromObjects()
        if (missing.isNotEmpty()) {
            record("Zone objects missing from objects: $missing")
        }
    }

    private fun record(violation: String) {
        violations.add(violation)
        if (strict) {
            throw AssertionError("ValidatingMessageSink: $violation")
        }
    }
}
