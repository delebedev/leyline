package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File

/**
 * Conformance pipeline: engine run → JSON dump → Python binding + diff.
 *
 * These tests exercise the automated conformance pipeline:
 * 1. Load a puzzle (auto-generated from recording segment)
 * 2. Perform the target action (playLand, castSpell, etc.)
 * 3. Serialize engine output to recording-compatible JSON
 * 4. Write to build/conformance/ for Python-side binding + diff
 *
 * The Python side (md-segments.py bind/diff) reads these files and compares
 * against templatized recording segments.
 */
class ConformancePipelineTest :
    FunSpec({

        tags(IntegrationTag)

        val outputDir = File("build/conformance").also { it.mkdirs() }

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("PlayLand segment: engine produces matching annotation structure") {
            val h = MatchFlowHarness(validating = false)
            harness = h

            // Use inline puzzle matching the recording's PlayLand segment:
            // Island in hand (will be played), other cards as filler.
            h.connectAndKeepPuzzleText(
                """
                [metadata]
                Name:Conformance PlayLand
                Goal:Win
                Turns:10
                Difficulty:Tutorial
                Description:Pipeline test

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Island;Island;Island;Wall of Runes;Sworn Guardian;Island;Island
                humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island
                aibattlefield=Plains
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """.trimIndent(),
            )

            val snap = h.messageSnapshot()
            h.playLand().shouldBeTrue()
            val msgs = h.messagesSince(snap)

            // Verify basic structure
            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }

            allAnnotations.shouldNotBeEmpty()

            // Extract the PlayLand frame
            val frame = AnnotationSerializer.extractByCategory(msgs, "PlayLand")
            frame.shouldNotBeNull()

            // Serialize to JSON for Python-side comparison
            val json = AnnotationSerializer.toRecordingJson(msgs)
            val outFile = File(outputDir, "playland-engine.json")
            outFile.writeText(json)

            // Also dump the PlayLand-specific frame
            val frameJson = buildString {
                append("{\n")
                val entries = frame.entries.toList()
                for ((i, entry) in entries.withIndex()) {
                    append("  \"${entry.key}\": ")
                    append(serializeValue(entry.value))
                    if (i < entries.size - 1) append(",")
                    append("\n")
                }
                append("}")
            }
            File(outputDir, "playland-frame.json").writeText(frameJson)
        }

        test("CastSpell segment: creature cast produces matching annotation structure") {
            val h = MatchFlowHarness(validating = false)
            harness = h

            // Recording: Wall of Runes (1U) cast from hand to stack.
            // Island already on battlefield provides blue mana.
            h.connectAndKeepPuzzleText(
                """
                [metadata]
                Name:Conformance CastSpell
                Goal:Win
                Turns:10
                Difficulty:Tutorial
                Description:Pipeline test — cast creature

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Wall of Runes;Island;Island;Sworn Guardian;Windstorm Drake;Waterknot
                humanbattlefield=Island
                humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island
                aibattlefield=Plains
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """.trimIndent(),
            )

            val snap = h.messageSnapshot()
            h.castSpellByName("Wall of Runes").shouldBeTrue()
            val msgs = h.messagesSince(snap)

            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }
            allAnnotations.shouldNotBeEmpty()

            val frame = AnnotationSerializer.extractByCategory(msgs, "CastSpell")
            frame.shouldNotBeNull()

            File(outputDir, "castspell-frame.json").writeText(
                buildString {
                    append("{\n")
                    val entries = frame.entries.toList()
                    for ((i, entry) in entries.withIndex()) {
                        append("  \"${entry.key}\": ")
                        append(serializeValue(entry.value))
                        if (i < entries.size - 1) append(",")
                        append("\n")
                    }
                    append("}")
                },
            )
        }
        test("DeclareAttackers prompt lifecycle capture") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Turn 1: play Mountain, cast Raging Goblin (haste)
            h.playLand().shouldBeTrue()
            h.castSpellByName("Raging Goblin").shouldBeTrue()
            h.passPriority() // resolve

            // Advance to combat — DeclareAttackersReq emitted
            h.passPriority()

            val daReqMsg = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
            daReqMsg.shouldNotBeNull()
            val initialReq = PromptSerializer.serialize(daReqMsg.declareAttackersReq)

            // Toggle one attacker (iterative DeclareAttackersResp)
            val attackerIid = daReqMsg.declareAttackersReq.attackersList.first().attackerInstanceId
            val echoMsgs = h.toggleAttackers(listOf(attackerIid))

            // Capture echo GSM
            val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
            val echoGsmJson = echoGsm?.let { buildEchoGsmJson(it) }

            // Capture echo DeclareAttackersReq
            val echoReqMsg = echoMsgs.firstOrNull { it.hasDeclareAttackersReq() }
            val echoReq = echoReqMsg?.let { PromptSerializer.serialize(it.declareAttackersReq) }

            // Submit
            h.submitAttackers()

            // Build lifecycle JSON matching template format
            val lifecycle = buildJsonObject {
                put("category", "DeclareAttackers")
                put("initial_req_promptData", initialReq)
                put("rounds", buildJsonArray {
                    add(buildJsonObject {
                        put("echo_gsms", buildJsonArray {
                            echoGsmJson?.let { add(it) }
                        })
                        echoReq?.let { put("echo_req_promptData", it) }
                    })
                })
            }

            val prettyJson = Json { prettyPrint = true }
            File(outputDir, "declare-attackers-lifecycle.json")
                .writeText(prettyJson.encodeToString(JsonElement.serializer(), lifecycle))
        }
    })

/** Build echo GSM JSON matching the template's conformance-relevant fields.
 *  Strips card-identity fields (grpId, power, subtypes, etc.) — same as
 *  segments.py's _extract_conformance_gsm. */
private fun buildEchoGsmJson(msg: GREToClientMessage): JsonObject {
    val gsm = msg.gameStateMessage
    val stripSuffix = Regex("_[a-f0-9]{3,4}$")
    return buildJsonObject {
        put("updateType", gsm.update.name.replace(stripSuffix, ""))
        put("objects", buildJsonArray {
            for (obj in gsm.gameObjectsList) {
                add(buildJsonObject {
                    put("instanceId", obj.instanceId)
                    put("zoneId", obj.zoneId)
                    put("type", obj.type.name.replace(stripSuffix, ""))
                    put("visibility", obj.visibility.name.replace(stripSuffix, ""))
                    put("owner", obj.ownerSeatId)
                    put("controller", obj.controllerSeatId)
                })
            }
        })
        put("annotations", buildJsonArray {
            for (ann in gsm.annotationsList) {
                add(buildJsonObject {
                    put("types", buildJsonArray {
                        ann.typeList.forEach { add(JsonPrimitive(it.name.replace(stripSuffix, ""))) }
                    })
                })
            }
        })
        put("persistentAnnotations", buildJsonArray {
            for (ann in gsm.persistentAnnotationsList) {
                add(buildJsonObject {
                    put("types", buildJsonArray {
                        ann.typeList.forEach { add(JsonPrimitive(it.name.replace(stripSuffix, ""))) }
                    })
                })
            }
        })
    }
}

/** Minimal JSON value serializer for test output. */
private fun serializeValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${value.replace("\"", "\\\"")}\""
    is Number -> "$value"
    is Boolean -> "$value"
    is List<*> -> "[${value.joinToString(", ") { serializeValue(it) }}]"
    is Map<*, *> -> {
        val entries = value.entries.toList()
        if (entries.isEmpty()) {
            "{}"
        } else {
            "{\n${entries.joinToString(",\n") { "    \"${it.key}\": ${serializeValue(it.value)}" }}\n  }"
        }
    }
    else -> "\"$value\""
}
