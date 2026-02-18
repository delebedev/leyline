package forge.nexus.debug

import forge.nexus.game.CardDb
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Structured game state collector for the debug panel.
 *
 * Extracts structured snapshots from outbound [GameStateMessage] protobufs,
 * stores them keyed by gsId, and computes diffs between any two snapshots.
 * Also collects priority trace events from [forge.nexus.server.MatchHandler]
 * decision points.
 *
 * Lives alongside [ArenaDebugCollector] — that stores raw JSON, this stores
 * structured/queryable data. Same integration pattern: explicit calls at
 * MatchHandler send sites.
 */
object GameStateCollector {
    private val log = LoggerFactory.getLogger(GameStateCollector::class.java)

    private const val MAX_SNAPSHOTS = 200
    private const val MAX_EVENTS = 500
    private val sseJson = Json { encodeDefaults = true }

    // --- Snapshots ---

    private val snapshots = LinkedHashMap<Int, StateSnapshot>()

    /** Collect game state snapshots from outbound GRE messages. */
    fun collectOutbound(messages: List<GREToClientMessage>, msgSeq: Int = 0) {
        for (gre in messages) {
            if (!gre.hasGameStateMessage()) continue
            val gs = gre.gameStateMessage
            if (gs.gameStateId <= 0) continue

            try {
                val snapshot = extractSnapshot(gs).copy(msgSeq = msgSeq)
                addSnapshot(snapshot)
            } catch (e: Exception) {
                log.debug("collectOutbound: failed to extract snapshot gsId={}: {}", gs.gameStateId, e.message)
            }
        }
    }

    /** All snapshots, ordered by gsId. */
    fun timeline(): List<StateSnapshot> = synchronized(snapshots) {
        snapshots.values.toList()
    }

    /** Single snapshot by gsId. */
    fun get(gsId: Int): StateSnapshot? = synchronized(snapshots) {
        snapshots[gsId]
    }

    /** Compute structured diff between two snapshots. */
    fun diff(fromGsId: Int, toGsId: Int): StateDiff? {
        val from = get(fromGsId) ?: return null
        val to = get(toGsId) ?: return null
        return computeDiff(from, to)
    }

    /** Query zone history for a specific instance across all snapshots. */
    fun instanceHistory(instanceId: Int): List<InstanceEvent> = synchronized(snapshots) {
        val events = mutableListOf<InstanceEvent>()
        var prevZoneId: Int? = null
        var prevZoneName: String? = null

        for (snapshot in snapshots.values) {
            val obj = snapshot.objects[instanceId]
            val currentZoneId = obj?.zoneId
            val currentZoneName = obj?.zoneName

            if (currentZoneId != prevZoneId) {
                events.add(
                    InstanceEvent(
                        gsId = snapshot.gsId,
                        ts = snapshot.ts,
                        instanceId = instanceId,
                        name = obj?.name,
                        fromZone = prevZoneName,
                        toZone = currentZoneName,
                        phase = snapshot.phase,
                        turn = snapshot.turn,
                    ),
                )
            }
            prevZoneId = currentZoneId
            prevZoneName = currentZoneName
        }
        return events
    }

    // --- Priority events ---

    private val eventBuffer = ArrayDeque<PriorityEvent>(MAX_EVENTS)
    private var eventSeq = 0

    /** Record a priority/engine decision event. */
    fun recordEvent(
        gsId: Int,
        type: EventType,
        phase: String?,
        turn: Int,
        detail: String,
        priorityPlayer: String? = null,
        stackDepth: Int = 0,
        msgSeq: Int = 0,
    ) {
        val event: PriorityEvent
        synchronized(eventBuffer) {
            eventSeq++
            event = PriorityEvent(
                seq = eventSeq,
                ts = System.currentTimeMillis(),
                gsId = gsId,
                type = type,
                phase = phase,
                turn = turn,
                detail = detail,
                priorityPlayer = priorityPlayer,
                stackDepth = stackDepth,
                msgSeq = msgSeq,
            )
            if (eventBuffer.size >= MAX_EVENTS) eventBuffer.removeFirst()
            eventBuffer.addLast(event)
        }
        try {
            DebugEventBus.emit("priority", sseJson.encodeToString(event))
        } catch (e: Exception) {
            log.debug("Failed to emit SSE priority event", e)
        }
    }

    /** Priority events since a given seq. */
    fun events(sinceSeq: Int = 0): List<PriorityEvent> = synchronized(eventBuffer) {
        eventBuffer.filter { it.seq > sinceSeq }
    }

    /** Clear all state (for test isolation). */
    fun clear() {
        synchronized(snapshots) { snapshots.clear() }
        synchronized(eventBuffer) {
            eventBuffer.clear()
            eventSeq = 0
        }
    }

    // --- Snapshot extraction ---

    private fun addSnapshot(snapshot: StateSnapshot) {
        synchronized(snapshots) {
            snapshots[snapshot.gsId] = snapshot
            // Evict oldest if over capacity
            while (snapshots.size > MAX_SNAPSHOTS) {
                val oldest = snapshots.keys.first()
                snapshots.remove(oldest)
            }
        }
        try {
            DebugEventBus.emit("state", sseJson.encodeToString(snapshot))
        } catch (e: Exception) {
            log.debug("Failed to emit SSE state snapshot", e)
        }
    }

    private fun extractSnapshot(gs: GameStateMessage): StateSnapshot {
        val ti = gs.turnInfo

        val zones = mutableMapOf<Int, ZoneSnapshot>()
        for (z in gs.zonesList) {
            zones[z.zoneId] = ZoneSnapshot(
                zoneId = z.zoneId,
                type = z.type.name.removeProtobufSuffix(),
                ownerSeatId = z.ownerSeatId,
                objectInstanceIds = z.objectInstanceIdsList.toList(),
            )
        }

        val objects = mutableMapOf<Int, ObjectSnapshot>()
        for (obj in gs.gameObjectsList) {
            val name = CardDb.getCardName(obj.grpId)
            objects[obj.instanceId] = ObjectSnapshot(
                instanceId = obj.instanceId,
                grpId = obj.grpId,
                type = obj.type.name.removeProtobufSuffix(),
                name = name,
                zoneId = obj.zoneId,
                zoneName = zones[obj.zoneId]?.type,
                ownerSeatId = obj.ownerSeatId,
                controllerSeatId = obj.controllerSeatId,
                power = if (obj.hasPower()) obj.power.value else null,
                toughness = if (obj.hasToughness()) obj.toughness.value else null,
                isTapped = obj.isTapped,
                hasSummoningSickness = obj.hasSummoningSickness,
                damage = obj.damage,
                loyalty = if (obj.hasLoyalty()) obj.loyalty.value.toInt() else null,
                attackState = if (obj.attackState != AttackState.None_a3a9) obj.attackState.name else null,
                blockState = if (obj.blockState != BlockState.None_aa2d) obj.blockState.name else null,
            )
        }

        val players = gs.playersList.map { p ->
            PlayerSnapshot(seatId = p.systemSeatNumber, life = p.lifeTotal)
        }

        val actions = gs.actionsList.map { a ->
            val action = a.action
            val name = if (action.instanceId != 0) {
                objects[action.instanceId]?.name
                    ?: CardDb.getCardName(action.grpId)
            } else {
                null
            }
            ActionSnapshot(
                actionType = action.actionType.name.removeProtobufSuffix(),
                instanceId = action.instanceId,
                grpId = action.grpId,
                name = name,
            )
        }

        val annotations = gs.annotationsList.map { ann ->
            val types = ann.typeList.map { it.name.removeProtobufSuffix() }
            val details = mutableMapOf<String, String>()
            for (kv in ann.detailsList) {
                val value = when {
                    kv.valueStringCount > 0 -> kv.valueStringList.joinToString(",")
                    kv.valueUint32Count > 0 -> kv.valueUint32List.joinToString(",")
                    else -> "?"
                }
                details[kv.key] = value
            }
            AnnotationSnapshot(
                types = types,
                affectedIds = ann.affectedIdsList.toList(),
                details = details,
            )
        }

        return StateSnapshot(
            gsId = gs.gameStateId,
            ts = System.currentTimeMillis(),
            type = gs.type.name.removeProtobufSuffix(),
            phase = ti.phase.name.removeProtobufSuffix(),
            step = ti.step.name.removeProtobufSuffix(),
            turn = ti.turnNumber,
            activePlayer = ti.activePlayer,
            priorityPlayer = ti.priorityPlayer,
            zones = zones,
            objects = objects,
            players = players,
            actions = actions,
            annotations = annotations,
        )
    }

    // --- Diff computation ---

    private fun computeDiff(from: StateSnapshot, to: StateSnapshot): StateDiff {
        // Phase/turn changes
        val phaseChange = if (from.phase != to.phase || from.step != to.step) {
            "${from.phase}/${from.step} -> ${to.phase}/${to.step}"
        } else {
            null
        }
        val turnChange = if (from.turn != to.turn) "${from.turn} -> ${to.turn}" else null

        // Zone deltas — compare objectInstanceIds lists
        val allZoneIds = (from.zones.keys + to.zones.keys).toSortedSet()
        val zoneDeltas = mutableListOf<ZoneDelta>()
        for (zoneId in allZoneIds) {
            val fromIds = from.zones[zoneId]?.objectInstanceIds?.toSet() ?: emptySet()
            val toIds = to.zones[zoneId]?.objectInstanceIds?.toSet() ?: emptySet()
            val added = (toIds - fromIds).toList()
            val removed = (fromIds - toIds).toList()
            if (added.isNotEmpty() || removed.isNotEmpty()) {
                val zoneName = to.zones[zoneId]?.type ?: from.zones[zoneId]?.type ?: "?"
                zoneDeltas.add(
                    ZoneDelta(
                        zoneId = zoneId,
                        type = zoneName,
                        added = added.map { id ->
                            InstanceRef(id, to.objects[id]?.name)
                        },
                        removed = removed.map { id ->
                            InstanceRef(id, from.objects[id]?.name)
                        },
                    ),
                )
            }
        }

        // Object deltas — added, removed, or field changes
        val allInstanceIds = (from.objects.keys + to.objects.keys).toSortedSet()
        val objectDeltas = mutableListOf<ObjectDelta>()
        for (id in allInstanceIds) {
            val fromObj = from.objects[id]
            val toObj = to.objects[id]
            when {
                fromObj == null && toObj != null -> objectDeltas.add(
                    ObjectDelta(id, toObj.name, "added", emptyMap()),
                )
                fromObj != null && toObj == null -> objectDeltas.add(
                    ObjectDelta(id, fromObj.name, "removed", emptyMap()),
                )
                fromObj != null && toObj != null -> {
                    val changes = diffObject(fromObj, toObj)
                    if (changes.isNotEmpty()) {
                        objectDeltas.add(ObjectDelta(id, toObj.name, "modified", changes))
                    }
                }
            }
        }

        // Player deltas
        val playerDeltas = mutableListOf<PlayerDelta>()
        val fromPlayers = from.players.associateBy { it.seatId }
        for (p in to.players) {
            val fp = fromPlayers[p.seatId]
            if (fp != null && fp.life != p.life) {
                playerDeltas.add(PlayerDelta(p.seatId, "${fp.life} -> ${p.life}"))
            }
        }

        // Actions summary
        val fromActions = from.actions.map { it.actionType }.sorted()
        val toActions = to.actions.map { it.actionType }.sorted()
        val actionsSummary = if (fromActions != toActions) {
            val toCounts = to.actions.groupBy { it.actionType }
                .map { (t, v) -> "$t=${v.size}" }
                .joinToString(" ")
            toCounts.ifEmpty { "none" }
        } else {
            null
        }

        return StateDiff(
            fromGsId = from.gsId,
            toGsId = to.gsId,
            phaseChange = phaseChange,
            turnChange = turnChange,
            zoneDeltas = zoneDeltas,
            objectDeltas = objectDeltas,
            playerDeltas = playerDeltas,
            actionsSummary = actionsSummary,
        )
    }

    /** Compare two ObjectSnapshots field by field. */
    private fun diffObject(a: ObjectSnapshot, b: ObjectSnapshot): Map<String, String> {
        val changes = mutableMapOf<String, String>()
        if (a.zoneId != b.zoneId) changes["zone"] = "${a.zoneName} -> ${b.zoneName}"
        if (a.controllerSeatId != b.controllerSeatId) changes["controller"] = "${a.controllerSeatId} -> ${b.controllerSeatId}"
        if (a.power != b.power) changes["power"] = "${a.power} -> ${b.power}"
        if (a.toughness != b.toughness) changes["toughness"] = "${a.toughness} -> ${b.toughness}"
        if (a.isTapped != b.isTapped) changes["tapped"] = "${a.isTapped} -> ${b.isTapped}"
        if (a.hasSummoningSickness != b.hasSummoningSickness) changes["sickness"] = "${a.hasSummoningSickness} -> ${b.hasSummoningSickness}"
        if (a.damage != b.damage) changes["damage"] = "${a.damage} -> ${b.damage}"
        if (a.loyalty != b.loyalty) changes["loyalty"] = "${a.loyalty} -> ${b.loyalty}"
        if (a.attackState != b.attackState) changes["attackState"] = "${a.attackState} -> ${b.attackState}"
        if (a.blockState != b.blockState) changes["blockState"] = "${a.blockState} -> ${b.blockState}"
        return changes
    }

    /** Strip protobuf enum suffixes like "_a549", "_add3", "_695e". */
    private fun String.removeProtobufSuffix(): String =
        replace(Regex("_[a-f0-9]{3,4}$"), "")

    // --- Data classes ---

    @Serializable
    data class StateSnapshot(
        val gsId: Int,
        val ts: Long,
        val type: String,
        val phase: String?,
        val step: String?,
        val turn: Int,
        val activePlayer: Int,
        val priorityPlayer: Int,
        val zones: Map<Int, ZoneSnapshot>,
        val objects: Map<Int, ObjectSnapshot>,
        val players: List<PlayerSnapshot>,
        val actions: List<ActionSnapshot>,
        val annotations: List<AnnotationSnapshot> = emptyList(),
        val msgSeq: Int = 0,
    )

    @Serializable
    data class AnnotationSnapshot(
        val types: List<String>,
        val affectedIds: List<Int>,
        val details: Map<String, String>,
    )

    @Serializable
    data class ZoneSnapshot(
        val zoneId: Int,
        val type: String,
        val ownerSeatId: Int,
        val objectInstanceIds: List<Int>,
    )

    @Serializable
    data class ObjectSnapshot(
        val instanceId: Int,
        val grpId: Int,
        val type: String,
        val name: String?,
        val zoneId: Int,
        val zoneName: String?,
        val ownerSeatId: Int,
        val controllerSeatId: Int,
        val power: Int?,
        val toughness: Int?,
        val isTapped: Boolean,
        val hasSummoningSickness: Boolean,
        val damage: Int,
        val loyalty: Int?,
        val attackState: String?,
        val blockState: String?,
    )

    @Serializable
    data class PlayerSnapshot(val seatId: Int, val life: Int)

    @Serializable
    data class ActionSnapshot(
        val actionType: String,
        val instanceId: Int,
        val grpId: Int,
        val name: String?,
    )

    @Serializable
    data class StateDiff(
        val fromGsId: Int,
        val toGsId: Int,
        val phaseChange: String?,
        val turnChange: String?,
        val zoneDeltas: List<ZoneDelta>,
        val objectDeltas: List<ObjectDelta>,
        val playerDeltas: List<PlayerDelta>,
        val actionsSummary: String?,
    )

    @Serializable
    data class ZoneDelta(
        val zoneId: Int,
        val type: String,
        val added: List<InstanceRef>,
        val removed: List<InstanceRef>,
    )

    @Serializable
    data class InstanceRef(val instanceId: Int, val name: String?)

    @Serializable
    data class ObjectDelta(
        val instanceId: Int,
        val name: String?,
        val changeType: String,
        val changes: Map<String, String>,
    )

    @Serializable
    data class PlayerDelta(val seatId: Int, val lifeChange: String?)

    @Serializable
    data class PriorityEvent(
        val seq: Int,
        val ts: Long,
        val gsId: Int,
        val type: EventType,
        val phase: String?,
        val turn: Int,
        val detail: String,
        val priorityPlayer: String? = null,
        val stackDepth: Int = 0,
        val msgSeq: Int = 0,
    )

    @Serializable
    data class InstanceEvent(
        val gsId: Int,
        val ts: Long,
        val instanceId: Int,
        val name: String?,
        val fromZone: String?,
        val toZone: String?,
        val phase: String?,
        val turn: Int,
    )

    @Serializable
    enum class EventType {
        PRIORITY_GRANT,
        AUTO_PASS,
        SEND_STATE,
        CLIENT_ACTION,
        COMBAT_PROMPT,
        TARGET_PROMPT,
        GAME_OVER,
        GAME_START,
        AI_TURN_WAIT,
        AI_TURN_TIMEOUT,
    }
}
