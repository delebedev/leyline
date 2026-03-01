package leyline.recording

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter

/**
 * CLI entry point for recording decoder.
 *
 * Usage: RecordingDecoderMain <recording-dir> [output.jsonl]
 *
 * Decodes all S-C_MATCH_DATA_*.bin files in the directory and writes
 * structured JSONL to stdout or the specified output file.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: RecordingDecoderMain <recording-dir> [output.jsonl] [--seat N]")
        System.err.println()
        System.err.println("Modes:")
        System.err.println("  <dir>                       Decode to JSONL")
        System.err.println("  <dir> --accumulate          Decode + accumulate state snapshots")
        System.err.println("  <dir> output.jsonl          Decode to file")
        System.err.println("  <dir> --accumulate out.jsonl Accumulate to file")
        System.err.println()
        System.err.println("Seat filtering:")
        System.err.println("  --seat 1   Player perspective (default)")
        System.err.println("  --seat 2   AI/Sparky perspective")
        System.err.println("  --seat 0   All seats interleaved (legacy)")
        System.exit(1)
        return
    }

    val dir = File(args[0])
    if (!dir.isDirectory) {
        System.err.println("Not a directory: $dir")
        System.exit(1)
        return
    }

    val accumulate = args.any { it == "--accumulate" }
    val outputPath = args.drop(1).firstOrNull { !it.startsWith("--") && it != args.getOrNull(args.indexOf("--seat") + 1) }

    // Parse --seat flag (default: 1 = player perspective)
    val seatFilter = parseSeatFilter(args.toList())

    // Print seat identification
    val seats = RecordingDecoder.detectSeats(dir)
    printSeatIdentification(seats)

    val messages = RecordingDecoder.decodeDirectory(dir, seatFilter)
    if (messages.isEmpty()) {
        System.err.println("No parseable MatchServiceToClientMessage payloads found in $dir")
        System.exit(1)
        return
    }

    val filterDesc = if (seatFilter != null) " (seat $seatFilter)" else " (all seats)"
    System.err.println("Decoded ${messages.size} GRE messages from ${dir.name}$filterDesc")

    if (accumulate) {
        writeAccumulated(messages, outputPath)
    } else {
        writeDecode(messages, outputPath)
    }
}

private fun writeDecode(messages: List<RecordingDecoder.DecodedMessage>, outputPath: String?) {
    val writer = if (outputPath != null) PrintWriter(File(outputPath)) else PrintWriter(System.out)
    for (msg in messages) {
        writer.println(RecordingDecoder.toJsonLine(msg))
    }
    writer.flush()
    if (outputPath != null) {
        writer.close()
        System.err.println("Wrote ${messages.size} lines to $outputPath")
    }
}

private fun writeAccumulated(messages: List<RecordingDecoder.DecodedMessage>, outputPath: String?) {
    val writer = if (outputPath != null) PrintWriter(File(outputPath)) else PrintWriter(System.out)
    val acc = AccumulatorSimulator()

    for (msg in messages) {
        acc.process(msg)
        val snapshot = acc.snapshot(msg.index, msg.gsId)
        writer.println(snapshot)
    }

    writer.flush()
    if (outputPath != null) {
        writer.close()
        System.err.println("Wrote ${messages.size} accumulated snapshots to $outputPath")
    }
}

/**
 * Simulates the MTGA client's state accumulator.
 *
 * Processes decoded messages and maintains accumulated state:
 * - Full GSM: replace all objects + zones
 * - Diff GSM: delete diffDeletedInstanceIds, then upsert objects + zones
 * - Tracks invariant violations at each step
 */
class AccumulatorSimulator {
    /** instanceId -> latest object summary */
    val objects = mutableMapOf<Int, RecordingDecoder.ObjectSummary>()

    /** zoneId -> latest zone summary */
    val zones = mutableMapOf<Int, RecordingDecoder.ZoneSummary>()

    /** Latest turnInfo */
    var turnInfo: RecordingDecoder.TurnInfoSummary? = null

    /** seat -> latest player summary */
    val players = mutableMapOf<Int, RecordingDecoder.PlayerSummary>()

    /** High-water gsId */
    var latestGsId: Int = 0

    fun process(msg: RecordingDecoder.DecodedMessage) {
        when (msg.gsmType) {
            "Full" -> {
                objects.clear()
                zones.clear()
                msg.objects.forEach { objects[it.instanceId] = it }
                msg.zones.forEach { zones[it.zoneId] = it }
            }
            "Diff" -> {
                msg.diffDeletedInstanceIds.forEach { objects.remove(it) }
                msg.objects.forEach { objects[it.instanceId] = it }
                msg.zones.forEach { zones[it.zoneId] = it }
            }
        }
        if (msg.turnInfo != null) turnInfo = msg.turnInfo
        for (p in msg.players) players[p.seat] = p
        if (msg.gsId > latestGsId) latestGsId = msg.gsId
    }

    /** Produce a JSON snapshot of the current accumulated state. */
    fun snapshot(afterIndex: Int, afterGsId: Int): String {
        val zoneRefsMissing = mutableListOf<String>()
        val objectsNotInZones = mutableListOf<Int>()

        for ((zoneId, z) in zones) {
            if (z.visibility == "Hidden" || z.visibility == "Private") continue
            if (z.type == "Limbo") continue
            for (oid in z.objectIds) {
                if (oid !in objects) zoneRefsMissing.add("z$zoneId:$oid")
            }
        }
        for ((iid, obj) in objects) {
            val zone = zones[obj.zoneId]
            if (zone == null || iid !in zone.objectIds) objectsNotInZones.add(iid)
        }

        val data = AccumulatorSnapshot(
            afterIndex = afterIndex,
            afterGsId = afterGsId,
            turnInfo = turnInfo?.let { SnapshotTurnInfo(it.phase, it.step, it.turn, it.activePlayer, it.priorityPlayer) },
            players = players.values.sortedBy { it.seat }.map { SnapshotPlayer(it.seat, it.life) },
            objectCount = objects.size,
            zoneCount = zones.size,
            zones = zones.toSortedMap().map { (id, z) ->
                id.toString() to SnapshotZone(z.type, z.owner, z.objectIds)
            }.toMap(),
            invariants = SnapshotInvariants(zoneRefsMissing, objectsNotInZones),
        )
        return snapshotJson.encodeToString(data)
    }
}

private val snapshotJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

@Serializable
private data class AccumulatorSnapshot(
    val afterIndex: Int,
    val afterGsId: Int,
    val turnInfo: SnapshotTurnInfo? = null,
    val players: List<SnapshotPlayer> = emptyList(),
    val objectCount: Int,
    val zoneCount: Int,
    val zones: Map<String, SnapshotZone> = emptyMap(),
    val invariants: SnapshotInvariants,
)

@Serializable
private data class SnapshotTurnInfo(
    val phase: String,
    val step: String,
    val turn: Int,
    val activePlayer: Int,
    val priorityPlayer: Int,
)

@Serializable
private data class SnapshotPlayer(val seat: Int, val life: Int)

@Serializable
private data class SnapshotZone(val type: String, val owner: Int, val objectIds: List<Int>)

@Serializable
private data class SnapshotInvariants(
    val zoneRefsMissingFromObjects: List<String> = emptyList(),
    val objectsNotInTheirZone: List<Int> = emptyList(),
)
