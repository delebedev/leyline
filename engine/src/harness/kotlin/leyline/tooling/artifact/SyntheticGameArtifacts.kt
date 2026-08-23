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

/** Writes the paired log/metadata lifecycle shared by synthetic callers. */
class SyntheticArtifactWriter(
    private val out: Writer,
    private val matchId: String,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val printer: JsonFormat.Printer =
        JsonFormat.printer().omittingInsignificantWhitespace().preservingProtoFieldNames()
    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
    private var gameStartEmitted = false

    /** Synthesize a ConnectResp so following GSMs form one contiguous game. */
    fun emitGameStart(seatId: Int = 1) {
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

    fun writeBundle(messages: List<GREToClientMessage>) {
        if (messages.isEmpty()) return
        if (!gameStartEmitted) emitGameStart()
        val event = GreToClientEvent.newBuilder().also { ev -> messages.forEach(ev::addGreToClientMessages) }.build()
        val wrapper = MatchServiceToClientMessage.newBuilder().setGreToClientEvent(event).build()
        val ts = clock().format(timestampFormat)
        out.write("[UnityCrossThreadLogger]$ts: Match to $matchId: GreToClientEvent\n")
        out.write(translateToScryFormat(printer.print(wrapper)))
        out.write("\n")
    }

    fun flush() = out.flush()

    private fun translateToScryFormat(json: String): String {
        val normalized =
            PlayerLogEnumJson.toCanonical(
                Json.parseToJsonElement(json),
                MatchServiceToClientMessage.getDescriptor(),
            )
        return Json.encodeToString(JsonElement.serializer(), normalized)
    }
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
