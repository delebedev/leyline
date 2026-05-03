package leyline.game.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.codes.DetailKeys
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/**
 * Unit tests for [InvariantChecker.checkPhaseFirst] and
 * [InvariantChecker.checkResolutionSandwich].
 *
 * PhaseOrStepModified, when present in a GSM, must be at index 0 of
 * the annotation list. Detection only — no enforcement here.
 *
 * Resolve-category ZoneTransfer annotations, when both ResolutionStart
 * and ResolutionComplete are present in the same GSM, must sit between
 * them. Detection only.
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

        fun zoneTransferAnnotation(
            id: Int,
            category: String,
            affectedId: Int = 100,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.ZoneTransfer_af5a)
                .addAffectedIds(affectedId)
                .addDetails(
                    KeyValuePairInfo
                        .newBuilder()
                        .setKey(DetailKeys.CATEGORY)
                        .setType(KeyValuePairValueType.String)
                        .addValueString(category)
                        .build(),
                ).build()

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

        // --- resolution_sandwich tests ---

        test("resolution_sandwich violation when Resolve ZT lands before ResolutionStart") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ObjectIdChanged),
                            zoneTransferAnnotation(2, "Resolve"),
                            annotation(3, AnnotationType.ResolutionStart),
                            annotation(4, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val sandwich = checker.violations.filter { it.check == "resolution_sandwich" }
            sandwich.shouldNotBeEmpty()
            sandwich.size shouldBe 1
        }

        test("resolution_sandwich violation when Resolve ZT lands after ResolutionComplete") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            annotation(2, AnnotationType.ResolutionComplete),
                            zoneTransferAnnotation(3, "Resolve"),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val sandwich = checker.violations.filter { it.check == "resolution_sandwich" }
            sandwich.shouldNotBeEmpty()
            sandwich.size shouldBe 1
        }

        test("resolution_sandwich records two violations when Resolve ZTs flank the bracket") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "Resolve", affectedId = 100),
                            annotation(2, AnnotationType.ResolutionStart),
                            annotation(3, AnnotationType.ResolutionComplete),
                            zoneTransferAnnotation(4, "Resolve", affectedId = 200),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val sandwich = checker.violations.filter { it.check == "resolution_sandwich" }
            sandwich.size shouldBe 2
        }

        test("no resolution_sandwich violation when Resolve ZT sits between RS and RC") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(2, "Resolve"),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_sandwich" }.shouldBeEmpty()
        }

        test("no resolution_sandwich violation when RS and RC are absent") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "Resolve"),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_sandwich" }.shouldBeEmpty()
        }

        test("no resolution_sandwich violation when non-Resolve ZT sits outside the bracket") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "CastSpell"),
                            annotation(2, AnnotationType.ResolutionStart),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_sandwich" }.shouldBeEmpty()
        }

        test("no resolution_sandwich violation when multiple Resolve ZTs all sit inside the bracket") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(2, "Resolve", affectedId = 100),
                            zoneTransferAnnotation(3, "Resolve", affectedId = 200),
                            annotation(4, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_sandwich" }.shouldBeEmpty()
        }
    })
