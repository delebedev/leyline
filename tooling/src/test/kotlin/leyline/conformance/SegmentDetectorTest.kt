package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.SearchReq

class SegmentDetectorTest :
    FunSpec({

        test("detects CastSpell segment from GSM with ZoneTransfer annotation") {
            val zt =
                AnnotationInfo
                    .newBuilder()
                    .addType(AnnotationType.ZoneTransfer_af5a)
                    .addDetails(
                        KeyValuePairInfo
                            .newBuilder()
                            .setKey("category")
                            .setType(KeyValuePairValueType.String)
                            .addValueString("CastSpell"),
                    ).build()
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .addAnnotations(zt)
                    .build()
            val gre =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.GameStateMessage_695e)
                    .setGameStateMessage(gsm)
                    .setGameStateId(10)
                    .build()
            val frame = RecordingFrameLoader.IndexedGREMessage(42, gre)

            val segments = SegmentDetector.detect(listOf(frame), "test-session")

            segments.size shouldBe 1
            segments[0].category shouldBe "CastSpell"
            segments[0].frameIndex shouldBe 42
            segments[0].gsId shouldBe 10
        }

        test("detects standalone SearchReq as its own segment") {
            val gre =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SearchReq_695e)
                    .setGameStateId(10)
                    .setSearchReq(SearchReq.newBuilder().setMaxFind(1))
                    .build()
            val frame = RecordingFrameLoader.IndexedGREMessage(99, gre)

            val segments = SegmentDetector.detect(listOf(frame), "test-session")

            segments.size shouldBe 1
            segments[0].category shouldBe "SearchReq"
        }

        test("skips GSM without ZoneTransfer") {
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .addAnnotations(
                        AnnotationInfo.newBuilder().addType(AnnotationType.PhaseOrStepModified),
                    ).build()
            val gre =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.GameStateMessage_695e)
                    .setGameStateMessage(gsm)
                    .build()
            val frame = RecordingFrameLoader.IndexedGREMessage(1, gre)

            val segments = SegmentDetector.detect(listOf(frame), "test-session")
            segments.size shouldBe 0
        }

        test("groups segments by category") {
            val session = "2026-03-21_22-05-00"
            if (!java.io.File("recordings/$session").exists()) return@test

            val frames = RecordingFrameLoader.load(session, seat = 0)
            val segments = SegmentDetector.detect(frames, session)
            val grouped = SegmentDetector.groupByCategory(segments)

            // Bushwhack recording has CastSpell, PlayLand, and SearchReq at minimum
            grouped.keys.shouldNotBeEmpty()
            grouped.forEach { (cat, segs) ->
                segs.shouldNotBeEmpty()
                segs.forEach { it.category shouldBe cat }
            }
        }
    })
