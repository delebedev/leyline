package leyline.tooling.artifact

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import leyline.protocol.PlayerLogEnumJson
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GreToClientEvent
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Run identity and provenance for one synthetic game artifact pair. */
data class SyntheticArtifactIdentity(
    val matchId: String,
    val runLabel: String,
    val seed: Long,
    val generatedAt: LocalDateTime,
    val runKind: String,
    val opponentRunLabel: String? = null,
    val quarantine: SyntheticArtifactQuarantine? = null,
)

/** Optional quarantine details preserved in the neutral sidecar schema. */
data class SyntheticArtifactQuarantine(
    val deck: SyntheticArtifactQuarantineSide? = null,
    val opponentDeck: SyntheticArtifactQuarantineSide? = null,
)

data class SyntheticArtifactQuarantineSide(
    val policy: String,
    val removedCount: Int,
    val removedCards: Int,
    val replacement: String? = null,
)

/** Writes the synthetic GRE log stream consumed by the run lifecycle. */
interface SyntheticArtifactSink {
    fun emitGameStart(seatId: Int = 1)

    fun writeBundle(messages: List<GREToClientMessage>)

    fun flush()
}

/** Writes the synthetic GRE log stream for callers that own the surrounding file. */
class SyntheticArtifactWriter(
    private val out: Writer,
    private val matchId: String,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : SyntheticArtifactSink {
    private val printer: JsonFormat.Printer =
        JsonFormat.printer().omittingInsignificantWhitespace().preservingProtoFieldNames()
    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
    private var gameStartEmitted = false

    /** Synthesize a ConnectResp so following GSMs form one contiguous game. */
    override fun emitGameStart(seatId: Int) {
        if (gameStartEmitted) return
        gameStartEmitted = true
        val ts = clock().format(timestampFormat)
        val connectJson =
            """{"greToClientEvent":{"greToClientMessages":[""" +
                """{"type":"GREMessageType_ConnectResp","systemSeatIds":[$seatId],"msgId":1,"gameStateId":0,""" +
                """"connectResp":{"status":"Status_Success","connectionInfo":{"matchId":"$matchId"}}}""" +
                """]}}"""
        out.write("[UnityCrossThreadLogger]$ts: Match to $matchId: GreToClientEvent\n")
        out.write(connectJson)
        out.write("\n")
    }

    override fun writeBundle(messages: List<GREToClientMessage>) {
        if (messages.isEmpty()) return
        if (!gameStartEmitted) emitGameStart()
        val event = GreToClientEvent.newBuilder().also { ev -> messages.forEach(ev::addGreToClientMessages) }.build()
        val wrapper = MatchServiceToClientMessage.newBuilder().setGreToClientEvent(event).build()
        val ts = clock().format(timestampFormat)
        out.write("[UnityCrossThreadLogger]$ts: Match to $matchId: GreToClientEvent\n")
        out.write(translateToScryFormat(printer.print(wrapper)))
        out.write("\n")
    }

    override fun flush() = out.flush()

    private fun translateToScryFormat(json: String): String {
        val normalized =
            PlayerLogEnumJson.toCanonical(
                Json.parseToJsonElement(json),
                MatchServiceToClientMessage.getDescriptor(),
            )
        return Json.encodeToString(JsonElement.serializer(), normalized)
    }
}

/** Owns one synthetic log/metadata pair from open through optional ingest. */
class SyntheticArtifactRun internal constructor(
    val logFile: File,
    private val out: Writer,
    private val writer: SyntheticArtifactWriter,
    private val identity: SyntheticArtifactIdentity,
) : SyntheticArtifactSink,
    AutoCloseable {
    private var finished = false

    override fun emitGameStart(seatId: Int) = writer.emitGameStart(seatId)

    override fun writeBundle(messages: List<GREToClientMessage>) = writer.writeBundle(messages)

    override fun flush() = writer.flush()

    /** Flushes, closes, writes metadata, and optionally ingests the exact pair. */
    @Synchronized
    fun finish(ingestTo: Path? = null) {
        if (!finished) {
            try {
                writer.flush()
            } finally {
                out.close()
            }
            writeSyntheticArtifactSidecar(logFile, identity)
            finished = true
        }
        if (ingestTo != null) ingestSyntheticArtifacts(logFile, ingestTo)
    }

    override fun close() = finish()
}

fun openSyntheticArtifactRun(
    logFile: File,
    identity: SyntheticArtifactIdentity,
    clock: () -> LocalDateTime = LocalDateTime::now,
): SyntheticArtifactRun {
    logFile.parentFile?.mkdirs()
    val out = logFile.bufferedWriter()
    return SyntheticArtifactRun(
        logFile = logFile,
        out = out,
        writer = SyntheticArtifactWriter(out = out, matchId = identity.matchId, clock = clock),
        identity = identity,
    )
}

fun writeSyntheticArtifactSidecar(
    logFile: File,
    identity: SyntheticArtifactIdentity,
) {
    val sidecar = File(logFile.parentFile, logFile.nameWithoutExtension + ".meta.json")
    val ts = identity.generatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val runTags =
        listOfNotNull(
            "simclient",
            "${identity.runKind}:${identity.runLabel}",
            identity.opponentRunLabel?.let { "opponent:$it" },
            "seed:${identity.seed}",
        ).joinToString(", ") { jsonString(it) }
    val eventName =
        if (identity.opponentRunLabel == null) {
            "simclient-${identity.runLabel}"
        } else {
            "simclient-${identity.runLabel}-vs-${identity.opponentRunLabel}"
        }
    val json =
        """
        {
          "cards": [],
          "tags": [$runTags],
          "notes": [],
          "quarantine": ${quarantineJson(identity.quarantine)},
          "provenance": {
            "source": "simclient",
            "confidence": "explicit",
            "matchId": ${jsonString(identity.matchId)},
            "eventName": ${jsonString(eventName)},
            "recordedAt": ${jsonString(ts)}
          }
        }
        """.trimIndent()
    sidecar.writeText(json)
}

fun ingestSyntheticArtifacts(
    logFile: File,
    gamesDir: Path = Path.of(System.getProperty("user.home"), ".scry", "games"),
) {
    Files.createDirectories(gamesDir)
    val base = logFile.nameWithoutExtension
    Files.copy(logFile.toPath(), gamesDir.resolve("$base.log"), StandardCopyOption.REPLACE_EXISTING)
    val sidecar = File(logFile.parentFile, "$base.meta.json")
    if (sidecar.exists()) {
        Files.copy(sidecar.toPath(), gamesDir.resolve("$base.meta.json"), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun quarantineJson(quarantine: SyntheticArtifactQuarantine?): String {
    if (quarantine == null) return "null"
    return buildString {
        append('{')
        append("\"deck\":${quarantineSideJson(quarantine.deck)},")
        append("\"opponentDeck\":${quarantineSideJson(quarantine.opponentDeck)}")
        append('}')
    }
}

private fun quarantineSideJson(side: SyntheticArtifactQuarantineSide?): String {
    if (side == null) return "null"
    return buildString {
        append('{')
        append("\"policy\":${jsonString(side.policy)},")
        append("\"removedCount\":${side.removedCount},")
        append("\"removedCards\":${side.removedCards},")
        append("\"replacement\":${side.replacement?.let(::jsonString) ?: "null"}")
        append('}')
    }
}

private fun jsonString(value: String): String =
    buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\', '"' -> append('\\').append(c)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
