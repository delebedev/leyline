package leyline.game.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType

/**
 * Unit tests for [InvariantChecker.checkPhaseFirst].
 *
 * PhaseOrStepModified, when present in a GSM, must be at index 0 of
 * the annotation list. Detection only — no enforcement here.
 */
class InvariantCheckerTest :
    FunSpec({

        tags(UnitTag)

        // --- Helpers (copied locally; small and self-contained) ---

        fun annotation(
            id: Int,
            type: AnnotationType,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(type)
                .build()

        fun gsm(
            gsId: Int,
            annotations: List<AnnotationInfo>,
        ): GameStateMessage =
            GameStateMessage
                .newBuilder()
                .setGameStateId(gsId)
                .setType(GameStateType.Full)
                .addAllAnnotations(annotations)
                .build()

        fun greMessage(
            msgId: Int,
            gsm: GameStateMessage,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateMessage(gsm)
                .build()

        // --- Tests ---

        test("phase_first violation when PhaseOrStepModified is not at index 0") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.AbilityInstanceCreated),
                            annotation(2, AnnotationType.CounterAdded),
                            annotation(3, AnnotationType.PhaseOrStepModified),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val phaseFirst = checker.violations.filter { it.check == "phase_first" }
            phaseFirst.shouldNotBeEmpty()
            phaseFirst.size shouldBe 1
        }

        test("no phase_first violation when PhaseOrStepModified is at index 0") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.PhaseOrStepModified),
                            annotation(2, AnnotationType.AbilityInstanceCreated),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "phase_first" }.shouldBeEmpty()
        }

        test("no phase_first violation when PhaseOrStepModified is absent") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.AbilityInstanceCreated),
                            annotation(2, AnnotationType.CounterAdded),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "phase_first" }.shouldBeEmpty()
        }

        test("no phase_first violation when multiple PhaseOrStepModified and first is at index 0") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.PhaseOrStepModified),
                            annotation(2, AnnotationType.PhaseOrStepModified),
                            annotation(3, AnnotationType.AbilityInstanceCreated),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "phase_first" }.shouldBeEmpty()
        }
    })
