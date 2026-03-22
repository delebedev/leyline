package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.*

class ProtoDifferTest :
    FunSpec({

        tags(UnitTag)

        test("identical messages produce empty diff") {
            val msg = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateId(1)
                .build()
            val result = ProtoDiffer.diff(msg, msg)
            result.isEmpty() shouldBe true
        }

        test("ID normalization: same structure, different IDs produce empty diff") {
            val gsm1 = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .addGameObjects(
                    GameObjectInfo.newBuilder()
                        .setInstanceId(200).setGrpId(98595)
                        .setType(GameObjectType.Card).setZoneId(28),
                )
                .build()
            val gsm2 = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff)
                .addGameObjects(
                    GameObjectInfo.newBuilder()
                        .setInstanceId(100).setGrpId(98595)
                        .setType(GameObjectType.Card).setZoneId(28),
                )
                .build()
            val msg1 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm1).build()
            val msg2 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm2).build()

            val result = ProtoDiffer.diff(msg1, msg2)
            result.isEmpty() shouldBe true
        }

        test("skip list: gameStateId difference not reported") {
            val msg1 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateId(50).build()
            val msg2 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateId(21).build()

            val result = ProtoDiffer.diff(msg1, msg2)
            result.isEmpty() shouldBe true
        }

        test("value mismatch reported with full path") {
            val gsm1 = GameStateMessage.newBuilder()
                .setType(GameStateType.Diff).build()
            val gsm2 = GameStateMessage.newBuilder()
                .setType(GameStateType.Full).build()
            val msg1 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm1).build()
            val msg2 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm2).build()

            val result = ProtoDiffer.diff(msg1, msg2)
            result.mismatched.size shouldBe 1
            result.mismatched[0].path.toString() shouldBe "gameStateMessage.type"
        }

        test("extra field in engine reported") {
            val obj1 = GameObjectInfo.newBuilder()
                .setInstanceId(100).setGrpId(98595)
                .setType(GameObjectType.Card).setZoneId(28)
                .build()
            val obj2 = GameObjectInfo.newBuilder()
                .setInstanceId(200).setGrpId(98595)
                .setType(GameObjectType.Card).setZoneId(28)
                .setIsTapped(true) // extra field — not in recording
                .build()
            val gsm1 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
                .addGameObjects(obj1).build()
            val gsm2 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
                .addGameObjects(obj2).build()
            val msg1 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm1).build()
            val msg2 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm2).build()

            val result = ProtoDiffer.diff(msg1, msg2)
            result.extra.any { it.toString().contains("isTapped") } shouldBe true
        }

        test("missing field reported") {
            val obj1 = GameObjectInfo.newBuilder()
                .setInstanceId(100).setGrpId(98595)
                .setType(GameObjectType.Card).setZoneId(28)
                .setIsTapped(true) // present in recording
                .build()
            val obj2 = GameObjectInfo.newBuilder()
                .setInstanceId(200).setGrpId(98595)
                .setType(GameObjectType.Card).setZoneId(28)
                // isTapped missing
                .build()
            val gsm1 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
                .addGameObjects(obj1).build()
            val gsm2 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
                .addGameObjects(obj2).build()
            val msg1 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm1).build()
            val msg2 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm2).build()

            val result = ProtoDiffer.diff(msg1, msg2)
            result.missing.any { it.toString().contains("isTapped") } shouldBe true
        }

        test("integration: SearchReq from recording has populated inner fields") {
            val session = "2026-03-21_22-05-00"
            if (!java.io.File("recordings/$session").exists()) return@test

            val searchReqs = RecordingFrameLoader.loadByType(
                session,
                GREMessageType.SearchReq_695e,
                seat = 1,
            )
            searchReqs.size shouldBe 1

            val req = searchReqs[0].message
            req.searchReq.maxFind shouldBe 1
            req.searchReq.zonesToSearchList.shouldNotBeEmpty()
            req.searchReq.itemsToSearchList.shouldNotBeEmpty()
            req.searchReq.itemsSoughtList.shouldNotBeEmpty()
        }

        test("annotations in different order produce empty diff") {
            fun annotation(type: AnnotationType, affectorId: Int) = AnnotationInfo.newBuilder()
                .addType(type)
                .setAffectorId(affectorId)
                .build()

            val gsm1 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
                .addAnnotations(annotation(AnnotationType.ZoneTransfer_af5a, 100))
                .addAnnotations(annotation(AnnotationType.ObjectIdChanged, 100))
                .build()
            val gsm2 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
                .addAnnotations(annotation(AnnotationType.ObjectIdChanged, 200)) // different order
                .addAnnotations(annotation(AnnotationType.ZoneTransfer_af5a, 200))
                .build()
            val msg1 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm1).build()
            val msg2 = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setGameStateMessage(gsm2).build()

            val result = ProtoDiffer.diff(msg1, msg2)
            result.isEmpty() shouldBe true
        }
    })
