package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.WireId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.event.DamageSourceKind
import leyline.tooling.headless.actionsMessage
import leyline.tooling.headless.greMessage
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.SuperType
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import java.io.StringWriter
import java.nio.file.Files
import java.time.LocalDateTime

class PlayerLogWriterTest :
    FunSpec({
        tags(UnitTag)

        test("sidecar escapes deck labels and records opponent tag") {
            val dir = Files.createTempDirectory("simclient-sidecar").toFile()
            val logFile = dir.resolve("quoted.log")

            writeSimClientSidecar(
                logFile = logFile,
                matchId = "match-\"quoted\"",
                runLabel = "Deck \"A\"",
                opponentRunLabel = "Blue\\Tempo",
                seed = 7,
                generatedAt = LocalDateTime.of(2026, 5, 1, 12, 0, 0),
            )

            val sidecar = dir.resolve("quoted.meta.json").readText()
            sidecar shouldBe
                """
                {
                  "cards": [],
                  "tags": ["simclient", "deck:Deck \"A\"", "opponent:Blue\\Tempo", "seed:7"],
                  "notes": [],
                  "quarantine": null,
                  "provenance": {
                    "source": "simclient",
                    "confidence": "explicit",
                    "matchId": "match-\"quoted\"",
                    "eventName": "simclient-Deck \"A\"-vs-Blue\\Tempo",
                    "recordedAt": "2026-05-01T12:00:00"
                  }
                }
                """.trimIndent()
        }

        test("ingest copies a generated log and its sidecar") {
            val source = Files.createTempDirectory("simclient-ingest-source").toFile()
            val target = Files.createTempDirectory("simclient-ingest-target")
            val logFile = source.resolve("acceptance-example.log").apply { writeText("trace") }
            source.resolve("acceptance-example.meta.json").writeText("{}")

            ingestSimClientArtifacts(logFile, target)

            target.resolve("acceptance-example.log").toFile().readText() shouldBe "trace"
            target.resolve("acceptance-example.meta.json").toFile().readText() shouldBe "{}"
        }

        test("writeBundle normalizes annotation enum suffixes") {
            val out = StringWriter()
            val writer =
                PlayerLogWriter(
                    out = out,
                    matchId = "match-annotations",
                    clock = { LocalDateTime.of(2026, 5, 1, 12, 0, 0) },
                )

            writer.writeBundle(
                listOf(
                    greMessage(msgId = 2, gsId = 1) {
                        addAnnotations(
                            AnnotationBuilder.zoneTransfer(
                                InstanceId(100),
                                srcZoneId = 31,
                                destZoneId = 27,
                                category = "CastSpell",
                            ),
                        )
                        addAnnotations(
                            AnnotationBuilder.damageDealt(
                                InstanceId(100),
                                targetId = WireId(2),
                                amount = 3,
                                sourceKind = DamageSourceKind.Combat,
                            ),
                        )
                        addAnnotations(
                            AnnotationBuilder.scry(
                                SeatId(1),
                                topIds = listOf(101),
                                bottomIds = emptyList(),
                            ),
                        )
                    },
                ),
            )

            val log = out.toString()
            val annotationTypes =
                Regex("\"type\":\\[\"(AnnotationType_[^\"]+)\"\\]")
                    .findAll(log)
                    .map { it.groupValues[1] }
                    .toList()

            annotationTypes shouldBe
                listOf(
                    "AnnotationType_ZoneTransfer",
                    "AnnotationType_DamageDealt",
                    "AnnotationType_Scry",
                )
            assertSoftly {
                log shouldNotContain "ZoneTransfer_af5a"
                log shouldNotContain "DamageDealt_af5a"
                log shouldNotContain "Scry_af5a"
            }
        }

        test("writeBundle canonicalizes common enum fields") {
            val out = StringWriter()
            val writer =
                PlayerLogWriter(
                    out = out,
                    matchId = "match-enums",
                    clock = { LocalDateTime.of(2026, 5, 1, 12, 0, 0) },
                )

            writer.writeBundle(
                listOf(
                    actionsMessage(msgId = 2, gsId = 1) {
                        addActions(
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Play_add3)
                                .addManaCost(ManaRequirement.newBuilder().addColor(ManaColor.Red_afc9).setCount(1)),
                        )
                        addActions(Action.newBuilder().setActionType(ActionType.ActivateMana))
                    },
                    greMessage(msgId = 3, gsId = 2) {
                        setType(GameStateType.Diff)
                        setUpdate(GameStateUpdate.SendHiFi)
                        setTurnInfo(
                            TurnInfo
                                .newBuilder()
                                .setPhase(Phase.Combat_a549)
                                .setStep(Step.DeclareAttack_a2cb),
                        )
                        addZones(ZoneInfo.newBuilder().setZoneId(27).setType(ZoneType.Battlefield))
                        addGameObjects(
                            GameObjectInfo
                                .newBuilder()
                                .setInstanceId(100)
                                .setGrpId(1)
                                .setType(GameObjectType.Card)
                                .setZoneId(27)
                                .addSuperTypes(SuperType.Basic)
                                .addCardTypes(CardType.Land_a80b)
                                .addSubtypes(SubType.Forest),
                        )
                        addAnnotations(
                            AnnotationBuilder
                                .zoneTransfer(
                                    InstanceId(100),
                                    srcZoneId = 31,
                                    destZoneId = 27,
                                    category = "CastSpell",
                                ).toBuilder()
                                .addDetails(
                                    KeyValuePairInfo
                                        .newBuilder()
                                        .setKey("example")
                                        .setType(KeyValuePairValueType.Uint32)
                                        .addValueUint32(1),
                                ),
                        )
                    },
                    GREToClientMessage
                        .newBuilder()
                        .setType(GREMessageType.PromptReq)
                        .setMsgId(4)
                        .setGameStateId(2)
                        .addSystemSeatIds(1)
                        .build(),
                ),
            )

            val log = out.toString()
            assertSoftly {
                log shouldNotContain "Play_add3"
                log shouldNotContain "Red_afc9"
                log shouldNotContain "Combat_a549"
                log shouldNotContain "DeclareAttack_a2cb"
                log shouldNotContain "Land_a80b"
                log shouldNotContain "GameStateMessage_695e"
                log shouldNotContain "ZoneTransfer_af5a"
                log shouldNotContain "\"type\":\"PromptReq\""

                log shouldBe log.replace("_add3", "")
                log shouldBe log.replace("_afc9", "")
                log shouldBe log.replace("_a549", "")
                log shouldBe log.replace("_a2cb", "")
                log shouldBe log.replace("_a80b", "")
                log shouldBe log.replace("_695e", "")

                log shouldContain "\"type\":\"GREMessageType_ActionsAvailableReq\""
                log shouldContain "\"type\":\"GREMessageType_GameStateMessage\""
                log shouldContain "\"type\":\"GREMessageType_PromptReq\""
                log shouldContain "\"type\":\"GameStateType_Diff\""
                log shouldContain "\"update\":\"GameStateUpdate_SendHiFi\""
                log shouldContain "\"actionType\":\"ActionType_Play\""
                log shouldContain "\"actionType\":\"ActionType_Activate_Mana\""
                log shouldContain "\"color\":[\"ManaColor_Red\"]"
                log shouldContain "\"phase\":\"Phase_Combat\""
                log shouldContain "\"step\":\"Step_DeclareAttack\""
                log shouldContain "\"type\":\"ZoneType_Battlefield\""
                log shouldContain "\"type\":\"GameObjectType_Card\""
                log shouldContain "\"superTypes\":[\"SuperType_Basic\"]"
                log shouldContain "\"cardTypes\":[\"CardType_Land\"]"
                log shouldContain "\"subtypes\":[\"SubType_Forest\"]"
                log shouldContain "\"type\":[\"AnnotationType_ZoneTransfer\"]"
                log shouldContain "\"type\":\"KeyValuePairValueType_uint32\""
            }
        }
    })
