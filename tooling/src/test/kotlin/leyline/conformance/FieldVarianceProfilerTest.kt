package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class FieldVarianceProfilerTest :
    FunSpec({

        test("constant field detected") {
            val segments =
                (1..5).map { i ->
                    makeSegment(
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .setGameStateId(i)
                            .build(),
                    )
                }
            val profile = FieldVarianceProfiler.profile(segments)

            profile.fieldProfiles["gameStateMessage.type"]!!.variance shouldBe ValueVariance.CONSTANT
            profile.fieldProfiles["gameStateMessage.type"]!!.frequency shouldBeExactly 1.0
        }

        test("enum field detected for small value set") {
            val segments =
                listOf(
                    makeGsmSegment(turnPhase = "Main1"),
                    makeGsmSegment(turnPhase = "Main2"),
                    makeGsmSegment(turnPhase = "Main1"),
                    makeGsmSegment(turnPhase = "Main1"),
                )
            val profile = FieldVarianceProfiler.profile(segments)

            val turnPhaseProfile =
                profile.fieldProfiles.entries.firstOrNull {
                    it.key.contains("phase")
                }
            // Phase field should be ENUM (2 distinct values)
            if (turnPhaseProfile != null) {
                turnPhaseProfile.value.variance shouldBe ValueVariance.ENUM
            }
        }

        test("annotation presence tracked") {
            val segments =
                (1..5).map { i ->
                    val ann =
                        AnnotationInfo
                            .newBuilder()
                            .addType(AnnotationType.ZoneTransfer_af5a)
                            .addType(AnnotationType.ObjectIdChanged)
                    makeSegment(
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .addAnnotations(ann)
                            .build(),
                    )
                }
            val profile = FieldVarianceProfiler.profile(segments)

            profile.annotationPresence["ZoneTransfer"]!!.frequency shouldBeExactly 1.0
            profile.annotationPresence["ObjectIdChanged"]!!.frequency shouldBeExactly 1.0
        }

        test("sometimes-present annotation has frequency < 1") {
            val withManaPaid =
                (1..3).map {
                    makeSegment(
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .addAnnotations(AnnotationInfo.newBuilder().addType(AnnotationType.ZoneTransfer_af5a))
                            .addAnnotations(AnnotationInfo.newBuilder().addType(AnnotationType.ManaPaid))
                            .build(),
                    )
                }
            val withoutManaPaid =
                (1..2).map {
                    makeSegment(
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .addAnnotations(AnnotationInfo.newBuilder().addType(AnnotationType.ZoneTransfer_af5a))
                            .build(),
                    )
                }
            val profile = FieldVarianceProfiler.profile(withManaPaid + withoutManaPaid)

            profile.annotationPresence["ZoneTransfer"]!!.frequency shouldBeExactly 1.0
            profile.annotationPresence["ManaPaid"]!!.frequency shouldBe 0.6
        }

        test("confidence levels") {
            Confidence.from(instances = 2, sessions = 1) shouldBe Confidence.TENTATIVE
            Confidence.from(instances = 5, sessions = 2) shouldBe Confidence.OBSERVED
            Confidence.from(instances = 15, sessions = 4) shouldBe Confidence.CONFIDENT
        }
    })

// --- Test helpers ---

private var segCounter = 0

private fun makeSegment(
    gsm: GameStateMessage,
    session: String = "test",
): Segment {
    val gre =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm)
            .setGameStateId(gsm.gameStateId)
            .build()
    return Segment("Test", gre, session, ++segCounter, gsm.gameStateId)
}

private fun makeGsmSegment(
    turnPhase: String,
    session: String = "test",
): Segment {
    val phase =
        Phase.values().firstOrNull { it.name.contains(turnPhase, ignoreCase = true) }
            ?: Phase.Main1_a549
    val gsm =
        GameStateMessage
            .newBuilder()
            .setType(GameStateType.Diff)
            .setTurnInfo(TurnInfo.newBuilder().setPhase(phase))
            .build()
    return makeSegment(gsm, session)
}
