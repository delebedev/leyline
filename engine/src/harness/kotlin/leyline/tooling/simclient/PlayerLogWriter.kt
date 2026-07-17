package leyline.tooling.simclient

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import leyline.protocol.PlayerLogEnumJson
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GreToClientEvent
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes a `<log>.meta.json` sidecar tagging a sim-client log file as
 * `source: simclient` (synthetic — neither a live client log nor a familiar match).
 *
 * scry-ts reads this for `--source` filtering and provenance display so that
 * synthetic logs stay separate from any reference data when downstream
 * comparison harnesses run.
 *
 * Schema mirrors scry-ts `GameMeta`: `provenance` block + empty cards/tags/notes.
 */
fun writeSimClientSidecar(
    logFile: File,
    matchId: String,
    runLabel: String,
    opponentRunLabel: String? = null,
    seed: Long,
    generatedAt: LocalDateTime,
    runKind: String = "deck",
    deckOverlay: DeckOverlayReport? = null,
    opponentDeckOverlay: DeckOverlayReport? = null,
) {
    val sidecar = File(logFile.parentFile, logFile.nameWithoutExtension + ".meta.json")
    val ts = generatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val runTags =
        listOfNotNull(
            "simclient",
            "$runKind:$runLabel",
            opponentRunLabel?.let { "opponent:$it" },
            "seed:$seed",
        ).joinToString(", ") { jsonString(it) }
    val eventName =
        if (opponentRunLabel == null) {
            "simclient-$runLabel"
        } else {
            "simclient-$runLabel-vs-$opponentRunLabel"
        }
    val quarantine = quarantineJson(deckOverlay, opponentDeckOverlay)
    val json =
        """
        {
          "cards": [],
          "tags": [$runTags],
          "notes": [],
          "quarantine": $quarantine,
          "provenance": {
            "source": "simclient",
            "confidence": "explicit",
            "matchId": ${jsonString(matchId)},
            "eventName": ${jsonString(eventName)},
            "recordedAt": ${jsonString(ts)}
          }
        }
        """.trimIndent()
    sidecar.writeText(json)
}

private fun quarantineJson(
    deckOverlay: DeckOverlayReport?,
    opponentDeckOverlay: DeckOverlayReport?,
): String {
    if (deckOverlay == null && opponentDeckOverlay == null) return "null"
    return buildString {
        append('{')
        append("\"deck\":${sidecarOverlayJson(deckOverlay)},")
        append("\"opponentDeck\":${sidecarOverlayJson(opponentDeckOverlay)}")
        append('}')
    }
}

private fun sidecarOverlayJson(report: DeckOverlayReport?): String {
    if (report == null) return "null"
    val policy = if (report.policy == SimClientExcludePolicy.ReplaceBasic) "replace-basic" else "skip-deck"
    return buildString {
        append('{')
        append("\"policy\":${jsonString(policy)},")
        append("\"removedCount\":${report.removedCount},")
        append("\"removedCards\":${report.removedCards},")
        append("\"replacement\":${report.replacement?.let(::jsonString) ?: "null"}")
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

/**
 * Writes Player.log-shaped lines for outbound GRE traffic.
 *
 * Each [writeBundle] call emits one block:
 *
 *   `[UnityCrossThreadLogger]MM/dd/yyyy HH:mm:ss: Match to <matchId>: GreToClientEvent`
 *   `<JSON wrapper>`
 *
 * scry-ts ingests this output. [translateToScryFormat] rewrites generated
 * protobuf enum symbols into canonical Player.log-style enum names.
 *
 * [emitGameStart] synthesises a ConnectResp before the first GSM bundle so
 * scry-ts treats the rest of the log as a single contiguous game.
 */
class PlayerLogWriter(
    private val out: Writer,
    private val matchId: String,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val printer: JsonFormat.Printer =
        JsonFormat.printer().omittingInsignificantWhitespace().preservingProtoFieldNames()
    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
    private var gameStartEmitted = false

    /** Synthesise a ConnectResp so scry-ts treats following GSMs as one game. */
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
        val event =
            GreToClientEvent.newBuilder().also { ev -> messages.forEach { ev.addGreToClientMessages(it) } }.build()
        val wrapper = MatchServiceToClientMessage.newBuilder().setGreToClientEvent(event).build()
        val ts = clock().format(timestampFormat)
        out.write("[UnityCrossThreadLogger]$ts: Match to $matchId: GreToClientEvent\n")
        out.write(translateToScryFormat(printer.print(wrapper)))
        out.write("\n")
    }

    fun flush() = out.flush()

    /** Rewrite generated proto enum values into canonical Player.log-style names. */
    private fun translateToScryFormat(json: String): String {
        val normalized =
            PlayerLogEnumJson.toCanonical(
                Json.parseToJsonElement(json),
                MatchServiceToClientMessage.getDescriptor(),
            )
        return Json.encodeToString(JsonElement.serializer(), normalized)
    }
}
