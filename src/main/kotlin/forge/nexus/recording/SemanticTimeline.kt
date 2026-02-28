package forge.nexus.recording

import forge.nexus.recording.RecordingDecoder.DecodedMessage

/**
 * Extracts a timeline of semantic game events from a [DecodedMessage] stream.
 *
 * Both recording captures and engine output get reduced to semantic events,
 * enabling structural comparison (diffing) between expected and actual game flows.
 */
object SemanticTimeline {

    // --- Semantic event types ---

    sealed interface Event

    /** Object moved between zones (draw, cast, play land, etc.). */
    data class ZoneTransfer(
        val origInstanceId: Int,
        val newInstanceId: Int,
        val category: String?,
        val destZoneType: String?,
    ) : Event

    /** Phase or step changed. */
    data class PhaseChange(
        val phase: String,
        val step: String,
        val activePlayer: Int,
        val priorityPlayer: Int,
    ) : Event

    /** A new turn started. */
    data class TurnStart(
        val turnNumber: Int,
        val activePlayer: Int,
    ) : Event

    /** Client prompted for actions. */
    data class ActionPrompt(
        val actionTypes: List<String>,
        val promptId: Int?,
    ) : Event

    /** Game ended (intermission). */
    data object GameOver : Event

    /** Tracks gsId chain progression. */
    data class GsIdStep(
        val gsId: Int,
        val prevGsId: Int?,
        val updateType: String?,
        val greType: String,
    ) : Event

    // --- Extraction ---

    /**
     * Extract semantic events from a decoded message stream.
     *
     * Processes annotations, turnInfo, action prompts, and gsId chain
     * to build an ordered timeline of game-meaningful events.
     */
    fun extract(messages: List<DecodedMessage>): List<Event> {
        val events = mutableListOf<Event>()

        for (msg in messages) {
            // GsIdStep for every message with gsId > 0
            if (msg.gsId > 0) {
                events.add(
                    GsIdStep(
                        gsId = msg.gsId,
                        prevGsId = msg.prevGsId,
                        updateType = msg.updateType,
                        greType = msg.greType,
                    ),
                )
            }

            // ZoneTransfer: pair ObjectIdChanged + ZoneTransfer annotations
            extractZoneTransfers(msg, events)

            // PhaseChange from PhaseOrStepModified annotation
            extractPhaseChanges(msg, events)

            // TurnStart from NewTurnStarted annotation
            extractTurnStarts(msg, events)

            // ActionPrompt when hasActionsAvailableReq
            if (msg.hasActionsAvailableReq) {
                events.add(
                    ActionPrompt(
                        actionTypes = msg.actions.map { it.type }.sorted(),
                        promptId = msg.promptId,
                    ),
                )
            }

            // GameOver when hasIntermissionReq
            if (msg.hasIntermissionReq) {
                events.add(GameOver)
            }
        }
        return events
    }

    /**
     * Extract ZoneTransfer events by pairing ObjectIdChanged + ZoneTransfer annotations.
     *
     * ObjectIdChanged: affectorId = old instanceId, affectedIds[0] = new instanceId
     * ZoneTransfer: affectorId = new instanceId, details["category"] = category string
     */
    private fun extractZoneTransfers(msg: DecodedMessage, out: MutableList<Event>) {
        // Build lookup: newInstanceId -> origInstanceId from ObjectIdChanged.
        // Real protos use details {orig_id, new_id} (affectorId is 0).
        val idChanges = mutableMapOf<Int, Int>() // newId -> origId
        for (ann in msg.annotations) {
            if ("ObjectIdChanged" !in ann.types) continue
            val origId = (ann.details["orig_id"] as? Number)?.toInt()
                ?: ann.affectorId.takeIf { it != 0 }
                ?: continue
            val newId = (ann.details["new_id"] as? Number)?.toInt()
                ?: ann.affectedIds.firstOrNull()
                ?: continue
            idChanges[newId] = origId
        }

        // Find ZoneTransfer annotations and pair with ObjectIdChanged.
        // ZoneTransfer.affectedIds[0] = newInstanceId (affectorId may be 0).
        for (ann in msg.annotations) {
            if ("ZoneTransfer" !in ann.types) continue
            val newInstanceId = ann.affectedIds.firstOrNull()
                ?: ann.affectorId.takeIf { it != 0 }
                ?: continue
            val origInstanceId = idChanges[newInstanceId] ?: newInstanceId
            val category = ann.details["category"]?.toString()
            val destZoneType = findZoneTypeForObject(msg, newInstanceId)

            out.add(
                ZoneTransfer(
                    origInstanceId = origInstanceId,
                    newInstanceId = newInstanceId,
                    category = category,
                    destZoneType = destZoneType,
                ),
            )
        }
    }

    /** Find the zone type containing an object instanceId. */
    private fun findZoneTypeForObject(msg: DecodedMessage, instanceId: Int): String? =
        msg.zones.firstOrNull { instanceId in it.objectIds }?.type

    private fun extractPhaseChanges(msg: DecodedMessage, out: MutableList<Event>) {
        for (ann in msg.annotations) {
            if ("PhaseOrStepModified" !in ann.types) continue
            val ti = msg.turnInfo ?: continue
            out.add(
                PhaseChange(
                    phase = ti.phase,
                    step = ti.step,
                    activePlayer = ti.activePlayer,
                    priorityPlayer = ti.priorityPlayer,
                ),
            )
        }
    }

    private fun extractTurnStarts(msg: DecodedMessage, out: MutableList<Event>) {
        for (ann in msg.annotations) {
            if ("NewTurnStarted" !in ann.types) continue
            val ti = msg.turnInfo ?: continue
            out.add(
                TurnStart(
                    turnNumber = ti.turn,
                    activePlayer = ti.activePlayer,
                ),
            )
        }
    }

    // --- Diff ---

    /**
     * Compare two event timelines and return human-readable divergence descriptions.
     *
     * Empty list = timelines match structurally.
     */
    fun diff(expected: List<Event>, actual: List<Event>): List<String> = buildList {
        val expectedGsSteps = expected.filterIsInstance<GsIdStep>()
        val actualGsSteps = actual.filterIsInstance<GsIdStep>()
        diffGsIdSteps(expectedGsSteps, actualGsSteps, this)

        val expectedTransfers = expected.filterIsInstance<ZoneTransfer>()
        val actualTransfers = actual.filterIsInstance<ZoneTransfer>()
        diffZoneTransfers(expectedTransfers, actualTransfers, this)

        val expectedPhases = expected.filterIsInstance<PhaseChange>()
        val actualPhases = actual.filterIsInstance<PhaseChange>()
        if (expectedPhases.size != actualPhases.size) {
            add("PhaseChange count: expected ${expectedPhases.size}, actual ${actualPhases.size}")
        }

        val expectedTurns = expected.filterIsInstance<TurnStart>()
        val actualTurns = actual.filterIsInstance<TurnStart>()
        if (expectedTurns.size != actualTurns.size) {
            add("TurnStart count: expected ${expectedTurns.size}, actual ${actualTurns.size}")
        }
    }

    private fun diffGsIdSteps(
        expected: List<GsIdStep>,
        actual: List<GsIdStep>,
        out: MutableList<String>,
    ) {
        if (expected.size != actual.size) {
            out.add("GsIdStep count: expected ${expected.size}, actual ${actual.size}")
        }

        val expTypes = expected.map { it.updateType }
        val actTypes = actual.map { it.updateType }
        if (expTypes != actTypes) {
            out.add("updateType sequence diverges: expected $expTypes, actual $actTypes")
        }
    }

    private fun diffZoneTransfers(
        expected: List<ZoneTransfer>,
        actual: List<ZoneTransfer>,
        out: MutableList<String>,
    ) {
        if (expected.size != actual.size) {
            out.add("ZoneTransfer count: expected ${expected.size}, actual ${actual.size}")
        }

        val expCategories = expected.map { it.category }
        val actCategories = actual.map { it.category }
        if (expCategories != actCategories) {
            out.add("ZoneTransfer categories diverge: expected $expCategories, actual $actCategories")
        }

        val expDest = expected.map { it.destZoneType }
        val actDest = actual.map { it.destZoneType }
        if (expDest != actDest) {
            out.add("ZoneTransfer destZoneTypes diverge: expected $expDest, actual $actDest")
        }
    }
}
