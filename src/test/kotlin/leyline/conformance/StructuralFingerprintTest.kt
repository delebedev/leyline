package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class StructuralFingerprintTest :
    FunSpec({

        test("extractFromGameStateMessage") {
            // Build a ZoneTransfer annotation with PlayLand category
            val annotation = AnnotationInfo.newBuilder()
                .addType(AnnotationType.ZoneTransfer_af5a)
                .addAffectedIds(100)
                .addDetails(
                    KeyValuePairInfo.newBuilder()
                        .setKey("category")
                        .addValueString("PlayLand"),
                )
                .build()

            // Build a Pass action embedded in the game state
            val passAction = ActionInfo.newBuilder()
                .setActionId(1)
                .setSeatId(1)
                .setAction(Action.newBuilder().setActionType(ActionType.Pass))
                .build()

            val zone = ZoneInfo.newBuilder()
                .setZoneId(28)
                .setType(ZoneType.Battlefield)
                .setOwnerSeatId(0)
                .setVisibility(Visibility.Public)
                .addObjectInstanceIds(100)
                .build()

            val obj = GameObjectInfo.newBuilder()
                .setInstanceId(100)
                .setGrpId(12345)
                .setType(GameObjectType.Card)
                .setZoneId(28)
                .build()

            val gameState = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(5)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .addZones(zone)
                .addGameObjects(obj)
                .addAnnotations(annotation)
                .addActions(passAction)
                .build()

            val gre = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(1)
                .setGameStateId(5)
                .addSystemSeatIds(1)
                .setGameStateMessage(gameState)
                .build()

            val fp = StructuralFingerprint.fromGRE(gre)

            fp.greMessageType shouldBe "GameStateMessage"
            fp.gsType shouldBe "Diff"
            fp.updateType shouldBe "SendAndRecord"
            fp.zoneCount shouldBe 1
            fp.objectCount shouldBe 1
            fp.annotationTypes shouldBe listOf("ZoneTransfer")
            fp.annotationCategories shouldBe listOf("PlayLand")
            fp.fieldPresence shouldContain "zones"
            fp.fieldPresence shouldContain "objects"
            fp.fieldPresence shouldContain "annotations"
            fp.fieldPresence shouldContain "actions"
            fp.actionTypes shouldBe listOf("Pass")
            fp.hasPrompt.shouldBeFalse()
            fp.promptId.shouldBeNull()
        }

        test("extractFromActionsAvailableReq") {
            val actions = ActionsAvailableReq.newBuilder()
                .addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(100)
                        .setGrpId(12345),
                )
                .addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(200)
                        .setGrpId(67890),
                )
                .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                .build()

            val gre = GREToClientMessage.newBuilder()
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .setMsgId(2)
                .setGameStateId(5)
                .addSystemSeatIds(1)
                .setActionsAvailableReq(actions)
                .setPrompt(Prompt.newBuilder().setPromptId(2).build())
                .build()

            val fp = StructuralFingerprint.fromGRE(gre)

            fp.greMessageType shouldBe "ActionsAvailableReq"
            fp.gsType.shouldBeNull()
            fp.updateType.shouldBeNull()
            // Action types should be sorted for deterministic comparison
            fp.actionTypes shouldBe listOf("Cast", "Pass", "Play")
            fp.hasPrompt.shouldBeTrue()
            fp.promptId shouldBe 2
            fp.zoneCount shouldBe 0
            fp.objectCount shouldBe 0
        }

        test("fingerprintsMatchAcrossDifferentIds") {
            // Two GRE messages with same structure but different IDs
            fun buildGRE(instanceId: Int, grpId: Int, gsId: Int, zoneId: Int): GREToClientMessage {
                val zone = ZoneInfo.newBuilder()
                    .setZoneId(zoneId)
                    .setType(ZoneType.Battlefield)
                    .setOwnerSeatId(0)
                    .setVisibility(Visibility.Public)
                    .addObjectInstanceIds(instanceId)
                    .build()

                val obj = GameObjectInfo.newBuilder()
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setType(GameObjectType.Card)
                    .setZoneId(zoneId)
                    .build()

                val gameState = GameStateMessage.newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(gsId)
                    .setUpdate(GameStateUpdate.SendAndRecord)
                    .addZones(zone)
                    .addGameObjects(obj)
                    .build()

                return GREToClientMessage.newBuilder()
                    .setType(GREMessageType.GameStateMessage_695e)
                    .setMsgId(1)
                    .setGameStateId(gsId)
                    .addSystemSeatIds(1)
                    .setGameStateMessage(gameState)
                    .build()
            }

            val fp1 = StructuralFingerprint.fromGRE(buildGRE(100, 12345, 5, 28))
            val fp2 = StructuralFingerprint.fromGRE(buildGRE(999, 67890, 42, 31))

            fp1 shouldBe fp2
        }

        test("roundTripJsonSerialization") {
            val fp = StructuralFingerprint(
                greMessageType = "GameStateMessage",
                gsType = "Diff",
                updateType = "SendAndRecord",
                annotationTypes = listOf("ZoneTransfer"),
                annotationCategories = listOf("CastSpell"),
                fieldPresence = setOf("turnInfo", "zones", "objects", "annotations"),
                zoneCount = 3,
                objectCount = 5,
                actionTypes = listOf("Pass"),
                hasPrompt = false,
                promptId = null,
            )
            val sequence = listOf(fp, fp.copy(gsType = "Full", zoneCount = 17))
            val json = GoldenSequence.toJson(sequence)
            val parsed = GoldenSequence.fromJson(json)
            parsed shouldBe sequence
        }

        test("loadGoldenFromFile") {
            val fp = StructuralFingerprint(
                greMessageType = "ActionsAvailableReq",
                actionTypes = listOf("Pass", "Play"),
                hasPrompt = true,
                promptId = 2,
            )
            val json = GoldenSequence.toJson(listOf(fp))
            val tempFile = java.io.File.createTempFile("golden-test", ".json")
            tempFile.writeText(json)
            tempFile.deleteOnExit()
            val loaded = GoldenSequence.fromFile(tempFile)
            loaded shouldBe listOf(fp)
        }
    })
