package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * Unit tests for focused [InvariantChecker] diagnostics and hard checks.
 *
 * Phase and resolution ordering checks are diagnostics, so these tests select
 * them explicitly instead of relying on the default hard-check set.
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

        fun aicAnnotation(
            id: Int,
            abilityIid: Int,
            affectorId: Int,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.AbilityInstanceCreated)
                .setAffectorId(affectorId)
                .addAffectedIds(abilityIid)
                .build()

        fun aidAnnotation(
            id: Int,
            abilityIid: Int,
            affectorId: Int,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.AbilityInstanceDeleted)
                .setAffectorId(affectorId)
                .addAffectedIds(abilityIid)
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

        fun checkerFor(
            reason: String,
            vararg checks: InvariantCheck,
        ) = InvariantChecker(InvariantSelection.only(reason, *checks))

        // --- Tests ---

        test("phase_first violation when PhaseOrStepModified is not at index 0") {
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
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
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
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
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
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
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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
            val checker = checkerFor("resolution diagnostic", InvariantCheck.ResolutionSandwich)
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

        // --- aid_affector tests ---

        test("aid_affector violation when AID affectorId differs from prior AIC affectorId across GSMs") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 416, affectorId = 372)))
            val g2 = gsm(gsId = 2, annotations = listOf(aidAnnotation(id = 1, abilityIid = 416, affectorId = 418)))

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            val mismatches = checker.violations.filter { it.check == "aid_affector" }
            assertSoftly {
                mismatches.size shouldBe 1
                mismatches[0].gsId shouldBe 2
                mismatches[0].message shouldContain "372"
                mismatches[0].message shouldContain "418"
            }
        }

        test("no aid_affector violation when AID affectorId matches prior AIC across GSMs") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 416, affectorId = 372)))
            val g2 = gsm(gsId = 2, annotations = listOf(aidAnnotation(id = 1, abilityIid = 416, affectorId = 372)))

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("no aid_affector violation when AID has no prior AIC for this ability iid") {
            val checker = InvariantChecker()
            val g = gsm(gsId = 1, annotations = listOf(aidAnnotation(id = 1, abilityIid = 416, affectorId = 999)))

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("no aid_affector violation for same-GSM AIC+AID pair (mana bracket)") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            aicAnnotation(id = 1, abilityIid = 100, affectorId = 200),
                            aidAnnotation(id = 2, abilityIid = 100, affectorId = 200),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("aid_affector tracks multiple ability iids independently") {
            val checker = InvariantChecker()
            val g1 =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            aicAnnotation(id = 1, abilityIid = 10, affectorId = 20),
                            aicAnnotation(id = 2, abilityIid = 30, affectorId = 40),
                        ),
                )
            val g2 =
                gsm(
                    gsId = 2,
                    annotations =
                        listOf(
                            aidAnnotation(id = 1, abilityIid = 10, affectorId = 999),
                            aidAnnotation(id = 2, abilityIid = 30, affectorId = 40),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            val mismatches = checker.violations.filter { it.check == "aid_affector" }
            assertSoftly {
                mismatches.size shouldBe 1
                mismatches[0].message shouldContain "ability=10"
            }
        }

        test("aid_affector entry is pruned after AID fires") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 50, affectorId = 60)))
            val g2 = gsm(gsId = 2, annotations = listOf(aidAnnotation(id = 1, abilityIid = 50, affectorId = 60)))
            val g3 = gsm(gsId = 3, annotations = listOf(aidAnnotation(id = 1, abilityIid = 50, affectorId = 999)))

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))
            checker.process(greMessage(msgId = 3, gsm = g3))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("aid_affector — same-GSM AIC+AID does not leak entry into history") {
            val checker = InvariantChecker()
            // GSM 1: AIC and AID for ability 100 in the same GSM (mana bracket).
            val g1 =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            aicAnnotation(id = 1, abilityIid = 100, affectorId = 200),
                            aidAnnotation(id = 2, abilityIid = 100, affectorId = 200),
                        ),
                )
            // GSM 2: standalone AID with wrong affector for ability 100 — should NOT
            // trip a violation, because the same-GSM AIC was pruned, not stored.
            val g2 =
                gsm(
                    gsId = 2,
                    annotations =
                        listOf(
                            aidAnnotation(id = 1, abilityIid = 100, affectorId = 999),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }
    })
