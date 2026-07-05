package leyline.tooling.simclient

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ControllerType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.GameType
import wotc.mtgo.gre.external.messaging.Messages.GameVariant
import wotc.mtgo.gre.external.messaging.Messages.GreToClientEvent
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.MatchScope
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchState
import wotc.mtgo.gre.external.messaging.Messages.MatchWinCondition
import wotc.mtgo.gre.external.messaging.Messages.MulliganType
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.PlayerStatus
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import wotc.mtgo.gre.external.messaging.Messages.ResultType
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.SuperFormat
import wotc.mtgo.gre.external.messaging.Messages.SuperType
import wotc.mtgo.gre.external.messaging.Messages.TeamStatus
import wotc.mtgo.gre.external.messaging.Messages.TimerBehavior
import wotc.mtgo.gre.external.messaging.Messages.TimerType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
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
        val normalized = normalizeEnumFields(Json.parseToJsonElement(json), fieldName = null, parentKeys = emptySet())
        return Json.encodeToString(JsonElement.serializer(), normalized)
    }

    private fun normalizeEnumFields(
        element: JsonElement,
        fieldName: String?,
        parentKeys: Set<String>,
    ): JsonElement =
        when (element) {
            is JsonObject -> {
                val keys = element.keys
                JsonObject(element.mapValues { (key, value) -> normalizeEnumFields(value, key, keys) })
            }
            is JsonArray -> JsonArray(element.map { normalizeEnumFields(it, fieldName, parentKeys) })
            is JsonPrimitive -> normalizePrimitiveEnum(element, fieldName, parentKeys)
        }

    private fun normalizePrimitiveEnum(
        element: JsonPrimitive,
        fieldName: String?,
        parentKeys: Set<String>,
    ): JsonElement {
        val value = element.contentOrNull ?: return element
        val normalized = enumMapFor(fieldName, parentKeys, value)?.get(value) ?: return element
        return JsonPrimitive(normalized)
    }

    private fun enumMapFor(
        fieldName: String?,
        parentKeys: Set<String>,
        value: String,
    ): Map<String, String>? = FIELD_ENUMS[fieldName] ?: contextualEnumMapFor(fieldName, parentKeys, value)

    private fun contextualEnumMapFor(
        fieldName: String?,
        parentKeys: Set<String>,
        value: String,
    ): Map<String, String>? =
        when (fieldName) {
            "status" -> statusEnumMapFor(parentKeys, value)
            "type" -> typeEnumMapFor(parentKeys, value)
            else -> null
        }

    private fun statusEnumMapFor(
        parentKeys: Set<String>,
        value: String,
    ): Map<String, String> =
        when {
            "playerIds" in parentKeys -> TEAM_STATUSES
            "systemSeatNumber" in parentKeys -> PLAYER_STATUSES
            value in TEAM_STATUSES -> TEAM_STATUSES
            else -> PLAYER_STATUSES
        }

    private fun typeEnumMapFor(
        parentKeys: Set<String>,
        value: String,
    ): Map<String, String>? =
        when {
            "msgId" in parentKeys && "systemSeatIds" in parentKeys -> GRE_MESSAGE_TYPES
            parentKeys.any { it in GRE_MESSAGE_PAYLOAD_FIELDS } -> GRE_MESSAGE_TYPES
            "timerId" in parentKeys -> TIMER_TYPES
            "matchID" in parentKeys || "gameNumber" in parentKeys -> GAME_TYPES
            "parameterName" in parentKeys -> PARAMETER_TYPES
            "gameStateId" in parentKeys -> GAME_STATE_TYPES
            "affectedIds" in parentKeys || "details" in parentKeys -> ANNOTATION_TYPES
            parentKeys.any { it in KEY_VALUE_VALUE_FIELDS } -> KEY_VALUE_TYPES
            "zoneId" in parentKeys && value in ZONE_TYPES -> ZONE_TYPES
            "grpId" in parentKeys && "zoneId" in parentKeys -> GAME_OBJECT_TYPES
            else -> null
        }

    companion object {
        private val ENUM_TAG_SUFFIX = Regex("_[a-f0-9]{4}$")

        private fun enumMap(
            values: Array<out Enum<*>>,
            prefix: String,
            canonicalName: (String) -> String = { it },
        ): Map<String, String> =
            buildMap {
                for (value in values) {
                    if (value.name == "UNRECOGNIZED") continue
                    val stripped = value.name.replace(ENUM_TAG_SUFFIX, "")
                    val canonical = "$prefix${canonicalName(stripped)}"
                    put(value.name, canonical)
                    put(stripped, canonical)
                    put(canonical, canonical)
                }
            }

        private val ACTION_TYPES =
            enumMap(ActionType.values(), "ActionType_") { name ->
                if (name == "ActivateMana") "Activate_Mana" else name
            }
        private val ANNOTATION_TYPES = enumMap(AnnotationType.values(), "AnnotationType_")
        private val CARD_TYPES = enumMap(CardType.values(), "CardType_")
        private val CLIENT_MESSAGE_TYPES = enumMap(ClientMessageType.values(), "ClientMessageType_")
        private val CONTROLLER_TYPES = enumMap(ControllerType.values(), "ControllerType_")
        private val GAME_STAGES = enumMap(GameStage.values(), "GameStage_")
        private val GAME_TYPES = enumMap(GameType.values(), "GameType_")
        private val GAME_VARIANTS = enumMap(GameVariant.values(), "GameVariant_")
        private val GAME_OBJECT_TYPES = enumMap(GameObjectType.values(), "GameObjectType_")
        private val GAME_STATE_TYPES = enumMap(GameStateType.values(), "GameStateType_")
        private val GAME_STATE_UPDATES = enumMap(GameStateUpdate.values(), "GameStateUpdate_")
        private val GRE_MESSAGE_TYPES = enumMap(GREMessageType.values(), "GREMessageType_")
        private val KEY_VALUE_TYPES =
            enumMap(KeyValuePairValueType.values(), "KeyValuePairValueType_") { name ->
                name.replaceFirstChar { it.lowercase() }
            }
        private val MANA_COLORS = enumMap(ManaColor.values(), "ManaColor_")
        private val MATCH_SCOPES = enumMap(MatchScope.values(), "MatchScope_")
        private val MATCH_STATES = enumMap(MatchState.values(), "MatchState_")
        private val MATCH_WIN_CONDITIONS = enumMap(MatchWinCondition.values(), "MatchWinCondition_")
        private val MULLIGAN_TYPES = enumMap(MulliganType.values(), "MulliganType_")
        private val PARAMETER_TYPES = enumMap(ParameterType.values(), "ParameterType_")
        private val PHASES = enumMap(Phase.values(), "Phase_")
        private val PLAYER_STATUSES = enumMap(PlayerStatus.values(), "PlayerStatus_")
        private val RESULT_REASONS = enumMap(ResultReason.values(), "ResultReason_")
        private val RESULT_TYPES = enumMap(ResultType.values(), "ResultType_")
        private val STEPS = enumMap(Step.values(), "Step_")
        private val SUB_TYPES = enumMap(SubType.values(), "SubType_")
        private val SUPER_FORMATS = enumMap(SuperFormat.values(), "SuperFormat_")
        private val SUPER_TYPES = enumMap(SuperType.values(), "SuperType_")
        private val TEAM_STATUSES = enumMap(TeamStatus.values(), "TeamStatus_")
        private val TIMER_BEHAVIORS = enumMap(TimerBehavior.values(), "TimerBehavior_")
        private val TIMER_TYPES = enumMap(TimerType.values(), "TimerType_")
        private val ZONE_TYPES = enumMap(ZoneType.values(), "ZoneType_")

        private val FIELD_ENUMS =
            mapOf(
                "actionType" to ACTION_TYPES,
                "behavior" to TIMER_BEHAVIORS,
                "controllerType" to CONTROLLER_TYPES,
                "matchState" to MATCH_STATES,
                "matchWinCondition" to MATCH_WIN_CONDITIONS,
                "mulliganType" to MULLIGAN_TYPES,
                "phase" to PHASES,
                "nextPhase" to PHASES,
                "reason" to RESULT_REASONS,
                "responseType" to CLIENT_MESSAGE_TYPES,
                "result" to RESULT_TYPES,
                "scope" to MATCH_SCOPES,
                "step" to STEPS,
                "nextStep" to STEPS,
                "stage" to GAME_STAGES,
                "superFormat" to SUPER_FORMATS,
                "variant" to GAME_VARIANTS,
                "cardTypes" to CARD_TYPES,
                "subtypes" to SUB_TYPES,
                "removedSubtypes" to SUB_TYPES,
                "superTypes" to SUPER_TYPES,
                "zoneType" to ZONE_TYPES,
                "update" to GAME_STATE_UPDATES,
                "color" to MANA_COLORS,
                "colors" to MANA_COLORS,
                "selectedColor" to MANA_COLORS,
                "manaColor" to MANA_COLORS,
                "manaColors" to MANA_COLORS,
            )

        private val KEY_VALUE_VALUE_FIELDS =
            setOf("valueUint32", "valueInt32", "valueUint64", "valueInt64", "valueBool", "valueString", "valueFloat", "valueDouble")

        private val GRE_MESSAGE_PAYLOAD_FIELDS =
            setOf(
                "connectResp",
                "gameStateMessage",
                "actionsAvailableReq",
                "declareAttackersReq",
                "declareBlockersReq",
                "selectTargetsReq",
                "submitTargetsResp",
                "submitAttackersResp",
                "submitBlockersResp",
                "groupReq",
                "mulliganReq",
                "orderReq",
                "intermissionReq",
                "optionalActionMessage",
                "assignDamageReq",
                "castingTimeOptionsReq",
                "numericInputReq",
                "searchReq",
                "selectNReq",
                "promptReq",
                "timerStateMessage",
                "queuedGameStateMessage",
                "edictalMessage",
                "dieRollResultsResp",
                "payCostsReq",
            )
    }
}
