package leyline.simclient

import com.google.protobuf.util.JsonFormat
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
    val json =
        """
        {
          "cards": [],
          "tags": [$runTags],
          "notes": [],
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
 * scry-ts ingests this output. Two translations are needed against leyline's
 * default JsonFormat output for the parser to recognise it:
 *
 * 1. **Type prefix.** scry-ts pattern-matches strings like
 *    `GREMessageType_GameStateMessage` and `GREMessageType_ConnectResp`.
 *    Leyline's proto enum values are `GameStateMessage_695e`, `ConnectResp_695e`,
 *    etc. (the suffix is a reverse-engineered tag). [translateToScryFormat]
 *    rewrites these to the prefixed, suffix-stripped form scry-ts expects.
 *
 * 2. **ConnectResp at game start.** scry-ts's [detectGames] function looks
 *    for a `GREMessageType_ConnectResp` to mark game boundaries. The simclient
 *    skips lobby + handshake, so no real ConnectResp ever fires. [emitGameStart]
 *    synthesises one before the first GSM bundle so scry-ts treats the rest of
 *    the log as a single contiguous game.
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

    /**
     * Rewrite leyline-proto enum values into the form scry-ts pattern-matches.
     *
     * Targeted: only rewrite top-level GRE message-type values (the ones
     * scry-ts checks literally: ConnectResp, GameStateMessage, etc.). Nested
     * `"type"` fields (TimerType, GameStateType, ZoneType, …) are left alone
     * because scry-ts already handles them with a tolerant `replace` that
     * accepts both prefixed and unprefixed forms.
     *
     * The allowlist below is the set of GRE message types relevant to scry-ts
     * game detection / GSM listing / prompt classification. Add to it when a
     * new message type needs to round-trip through scry-ts.
     */
    private fun translateToScryFormat(json: String): String {
        var out = json
        for (name in MESSAGE_TYPE_NAMES) {
            // Match `"type":"<Name>_<4hex>"` and `"type":"<Name>"` (no tag) and
            // rewrite to `"type":"GREMessageType_<Name>"`.
            out =
                out
                    .replace(""""type":"${name}_""".let { Regex(Regex.escape(it) + "[a-f0-9]{4}\"") }, """"type":"GREMessageType_$name"""")
                    .replace(""""type":"$name"""", """"type":"GREMessageType_$name"""")
        }
        return out
    }

    companion object {
        /** GRE message types scry-ts pattern-matches by literal `GREMessageType_<Name>`. */
        private val MESSAGE_TYPE_NAMES =
            listOf(
                "ConnectResp",
                "GameStateMessage",
                "ActionsAvailableReq",
                "DeclareAttackersReq",
                "DeclareBlockersReq",
                "SelectTargetsReq",
                "SubmitTargetsResp",
                "SubmitAttackersResp",
                "SubmitBlockersResp",
                "GroupReq",
                "MulliganReq",
                "IntermissionReq",
                "OptionalActionMessage",
                "AssignDamageReq",
                "CastingTimeOptionsReq",
                "NumericInputReq",
                "SearchReq",
                "SelectNReq",
                "PromptReq",
                "TimerStateMessage",
                "QueuedGameStateMessage",
                "EdictalMessage",
                "DieRollResultsResp",
                "PayCostsReq",
            )
    }
}
