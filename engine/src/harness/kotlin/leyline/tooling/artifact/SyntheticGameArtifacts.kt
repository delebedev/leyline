package leyline.tooling.artifact

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
@Serializable
data class SyntheticArtifactQuarantine(
    val deck: SyntheticArtifactQuarantineSide? = null,
    val opponentDeck: SyntheticArtifactQuarantineSide? = null,
)

@Serializable
data class SyntheticArtifactQuarantineSide(
    val policy: String,
    val removedCount: Int,
    val removedCards: Int,
    val replacement: String? = null,
)

@Serializable
private data class SyntheticArtifactMetadata(
    val cards: List<String>,
    val tags: List<String>,
    val notes: List<String>,
    val quarantine: SyntheticArtifactQuarantine?,
    val provenance: SyntheticArtifactProvenance,
)

@Serializable
private data class SyntheticArtifactProvenance(
    val source: String,
    val confidence: String,
    val matchId: String,
    val eventName: String,
    val recordedAt: String,
)

private val syntheticArtifactJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

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
        )
    val eventName =
        if (identity.opponentRunLabel == null) {
            "simclient-${identity.runLabel}"
        } else {
            "simclient-${identity.runLabel}-vs-${identity.opponentRunLabel}"
        }
    val json =
        syntheticArtifactJson.encodeToString(
            SyntheticArtifactMetadata(
                cards = emptyList(),
                tags = runTags,
                notes = emptyList(),
                quarantine = identity.quarantine,
                provenance =
                    SyntheticArtifactProvenance(
                        source = "simclient",
                        confidence = "explicit",
                        matchId = identity.matchId,
                        eventName = eventName,
                        recordedAt = ts,
                    ),
            ),
        )
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
