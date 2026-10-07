package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import leyline.game.annotations.AnnotationBuilder
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import leyline.game.sid
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/**
 * Unit tests for focused [InvariantChecker] diagnostics and hard checks.
 *
 * Phase and resolution ordering checks are diagnostics, so these tests select
 * them explicitly instead of relying on the default hard-check set.
 */
class InvariantCheckerTest :
    FunSpec({

        tags(UnitTag)

        test("ordinary transition identity rejects malformed pairs and projections") {
            fun detail(
                key: String,
                value: Int,
            ) = KeyValuePairInfo
                .newBuilder()
                .setKey(key)
                .addValueInt32(value)
                .build()

            fun zone(
                id: Int,
                type: ZoneType,
                vararg ids: Int,
            ) = ZoneInfo
                .newBuilder()
                .setZoneId(
                    id,
                ).setType(type)
                .setVisibility(Visibility.Public)
                .addAllObjectInstanceIds(ids.toList())
                .build()

            fun change(
                old: Int,
                new: Int,
            ) = AnnotationInfo
                .newBuilder()
                .addType(AnnotationType.ObjectIdChanged)
                .addDetails(detail(DetailKeys.ORIG_ID, old))
                .addDetails(detail(DetailKeys.NEW_ID, new))
                .build()

            fun transfer(
                id: Int,
                source: Int,
                destination: Int,
                category: String,
            ) = AnnotationInfo
                .newBuilder()
                .addType(AnnotationType.ZoneTransfer_af5a)
                .addAffectedIds(id)
                .addDetails(detail(DetailKeys.ZONE_SRC, source))
                .addDetails(detail(DetailKeys.ZONE_DEST, destination))
                .addDetails(KeyValuePairInfo.newBuilder().setKey(DetailKeys.CATEGORY).addValueString(category))
                .build()
            val cast = transfer(200, 31, 27, "CastSpell")
            val pair = change(100, 200)
            val stackObject =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(200)
                    .setZoneId(27)
                    .build()
            val clean =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addAnnotations(pair)
                    .addAnnotations(cast)
                    .addGameObjects(stackObject)
                    .addZones(zone(31, ZoneType.Hand))
                    .addZones(zone(27, ZoneType.Stack, 200))
                    .addZones(zone(30, ZoneType.Limbo, 100))
                    .build()

            fun check(gsm: GameStateMessage): List<InvariantChecker.Violation> =
                InvariantChecker().also { it.process(GREToClientMessage.newBuilder().setGameStateMessage(gsm).build()) }.violations
            check(clean).shouldBeEmpty()
            val mutants =
                listOf(
                    clean.toBuilder().setAnnotations(0, change(100, 201)).build(),
                    clean.toBuilder().setAnnotations(0, change(200, 200)).build(),
                    clean.toBuilder().setAnnotations(1, cast.toBuilder().clearAffectedIds().addAffectedIds(201)).build(),
                    clean.toBuilder().setZones(0, zone(31, ZoneType.Hand, 100)).build(),
                    clean.toBuilder().clearGameObjects().build(),
                    clean.toBuilder().setZones(1, zone(27, ZoneType.Stack)).build(),
                    clean
                        .toBuilder()
                        .clearAnnotations()
                        .addAnnotations(cast)
                        .addAnnotations(pair)
                        .build(),
                    clean.toBuilder().setAnnotations(1, cast.toBuilder().clearAffectedIds()).build(),
                )
            for (mutant in mutants) {
                check(mutant).map { it.check }.shouldContain(InvariantCheck.ZoneTransitionIdentity.id)
            }
            // Hidden destinations expose membership without requiring an object row.
            check(
                clean
                    .toBuilder()
                    .clearGameObjects()
                    .setZones(1, clean.getZones(1).toBuilder().setVisibility(Visibility.Hidden))
                    .build(),
            ).shouldBeEmpty()
            // A shuffle-only identity change is outside ordinary spell transfers.
            check(
                clean
                    .toBuilder()
                    .clearAnnotations()
                    .addAnnotations(change(100, 201))
                    .build(),
            ).shouldBeEmpty()
            val chain =
                clean
                    .toBuilder()
                    .clearGameObjects()
                    .addGameObjects(stackObject.toBuilder().setInstanceId(300).setZoneId(33))
                    .addAnnotations(change(200, 300))
                    .addAnnotations(transfer(300, 27, 33, "Resolve"))
                    .setZones(1, zone(27, ZoneType.Stack))
                    .setZones(2, zone(30, ZoneType.Limbo, 100, 200))
                    .addZones(zone(33, ZoneType.Graveyard, 300))
                    .build()
            check(chain).shouldBeEmpty()
            check(chain.toBuilder().setZones(1, zone(27, ZoneType.Stack, 200)).build()).shouldNotBeEmpty()
            check(chain.toBuilder().clearGameObjects().build()).shouldNotBeEmpty()
        }

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
            srcZoneId: Int = ZoneIds.STACK,
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
                ).addDetails(
                    KeyValuePairInfo
                        .newBuilder()
                        .setKey(DetailKeys.ZONE_SRC)
                        .setType(KeyValuePairValueType.Int32)
                        .addValueInt32(srcZoneId)
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

        fun resolutionStartAnnotation(
            id: Int,
            abilityIid: Int,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.ResolutionStart)
                .setAffectorId(abilityIid)
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

        // --- Persistent packet boundaries ---

        val persistentRow = annotation(42, AnnotationType.DamagedThisTurn)
        for ((name, rows, deletions) in listOf(
            Triple("duplicate persistent rows", listOf(persistentRow, persistentRow), emptyList()),
            Triple("duplicate persistent deletions", emptyList(), listOf(42, 42)),
            Triple("persistent emit and delete overlap", listOf(persistentRow), listOf(42)),
        )) {
            test("default checker rejects $name") {
                val checker = InvariantChecker()
                val packet =
                    gsm(1, emptyList())
                        .toBuilder()
                        .addAllPersistentAnnotations(rows)
                        .addAllDiffDeletedPersistentAnnotationIds(deletions)
                        .build()
                checker.process(greMessage(1, packet))
                checker.violations.single().check shouldBe "persistent_packet"
            }
        }

        test("persistent rows may rebroadcast and change membership affectors and details") {
            val checker = InvariantChecker()
            val updated =
                persistentRow
                    .toBuilder()
                    .setAffectorId(102)
                    .addAffectedIds(101)
                    .addAffectedIds(103)
                    .addDetails(
                        KeyValuePairInfo
                            .newBuilder()
                            .setKey("value")
                            .setType(KeyValuePairValueType.Int32)
                            .addValueInt32(2),
                    ).build()
            for ((index, row) in listOf(persistentRow, persistentRow, updated).withIndex()) {
                val packet =
                    gsm(index + 1, emptyList())
                        .toBuilder()
                        .setType(if (index == 0) GameStateType.Full else GameStateType.Diff)
                        .addPersistentAnnotations(row)
                        .build()
                checker.process(greMessage(index + 1, packet))
            }
            checker.violations.shouldBeEmpty()
        }

        test("Full seed and Undo baselines permit restored persistent rows") {
            val checker = InvariantChecker()
            checker.process(
                greMessage(
                    1,
                    gsm(1, emptyList())
                        .toBuilder()
                        .addPersistentAnnotations(persistentRow)
                        .build(),
                ),
            )
            checker.process(
                greMessage(
                    2,
                    gsm(2, emptyList())
                        .toBuilder()
                        .setType(GameStateType.Diff)
                        .addDiffDeletedPersistentAnnotationIds(42)
                        .build(),
                ),
            )
            checker.seedFull(
                gsm(3, emptyList())
                    .toBuilder()
                    .addPersistentAnnotations(persistentRow)
                    .build(),
            )
            checker.process(
                greMessage(
                    4,
                    gsm(4, emptyList())
                        .toBuilder()
                        .setUpdate(wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.Undo)
                        .addPersistentAnnotations(persistentRow)
                        .build(),
                ),
            )
            checker.process(
                greMessage(
                    5,
                    gsm(5, emptyList())
                        .toBuilder()
                        .setType(GameStateType.Diff)
                        .addPersistentAnnotations(persistentRow)
                        .build(),
                ),
            )
            checker.violations.shouldBeEmpty()
        }

        // --- Tests ---

        test("resumed Untap boundary is valid for both ordering diagnostics") {
            for (check in listOf(InvariantCheck.PhaseFirst, InvariantCheck.AnnotationOrdering)) {
                for (nextStep in listOf(2, 3)) {
                    val checker = checkerFor("resumed step", check)
                    checker.seedFull(
                        gsm(1, emptyList())
                            .toBuilder()
                            .setTurnInfo(
                                wotc.mtgo.gre.external.messaging.Messages.TurnInfo
                                    .newBuilder()
                                    .setStep(wotc.mtgo.gre.external.messaging.Messages.Step.Untap),
                            ).build(),
                    )
                    val resumed =
                        gsm(
                            2,
                            listOf(
                                annotation(1, AnnotationType.TappedUntappedPermanent),
                                AnnotationBuilder
                                    .phaseOrStepModified(1.sid, 1, nextStep)
                                    .toBuilder()
                                    .setId(2)
                                    .build(),
                            ),
                        ).toBuilder().setType(GameStateType.Diff).build()
                    checker.process(greMessage(1, resumed))
                    checker.violations.shouldBeEmpty()
                }
            }
        }

        test("ordering diagnostics retain turn info across Diff and replace it on Full or Undo") {
            for (check in listOf(InvariantCheck.PhaseFirst, InvariantCheck.AnnotationOrdering)) {
                for (reset in listOf(false, true)) {
                    val checker = checkerFor("step baseline", check)
                    checker.seedFull(
                        gsm(1, emptyList())
                            .toBuilder()
                            .setTurnInfo(
                                wotc.mtgo.gre.external.messaging.Messages.TurnInfo
                                    .newBuilder()
                                    .setStep(wotc.mtgo.gre.external.messaging.Messages.Step.Untap),
                            ).build(),
                    )
                    val emptyDiff = gsm(2, emptyList()).toBuilder().setType(GameStateType.Diff).build()
                    checker.process(greMessage(1, emptyDiff))
                    val resumed =
                        gsm(
                            3,
                            listOf(
                                annotation(1, AnnotationType.TappedUntappedPermanent),
                                AnnotationBuilder
                                    .phaseOrStepModified(1.sid, 1, 2)
                                    .toBuilder()
                                    .setId(2)
                                    .build(),
                            ),
                        ).toBuilder().setType(GameStateType.Diff).build()
                    checker.process(greMessage(2, resumed))
                    checker.violations.shouldBeEmpty()
                    val replacement =
                        if (reset) {
                            emptyDiff
                                .toBuilder()
                                .setUpdate(wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.Undo)
                                .build()
                        } else {
                            gsm(4, emptyList())
                                .toBuilder()
                                .setTurnInfo(
                                    wotc.mtgo.gre.external.messaging.Messages.TurnInfo
                                        .newBuilder()
                                        .setStep(wotc.mtgo.gre.external.messaging.Messages.Step.None_a2cb),
                                ).build()
                        }
                    checker.process(greMessage(3, replacement))
                    checker.process(greMessage(4, resumed.toBuilder().setGameStateId(5).build()))
                    checker.violations
                        .filter {
                            it.check ==
                                if (check ==
                                    InvariantCheck.PhaseFirst
                                ) {
                                    "phase_first"
                                } else {
                                    "annotation_ordering"
                                }
                        }.shouldNotBeEmpty()
                }
            }
        }

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

        // --- resolution_transfer_ordering tests ---

        fun resolutionChecker() = checkerFor("resolution diagnostic", InvariantCheck.ResolutionTransferOrdering)

        test("resolution_transfer_ordering violation when stack Resolve ZT lands before ResolutionStart") {
            val checker = resolutionChecker()
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

            val ordering = checker.violations.filter { it.check == "resolution_transfer_ordering" }
            ordering.shouldNotBeEmpty()
            ordering.size shouldBe 1
        }

        test("resolution_transfer_ordering violation when stack Resolve ZT lands between ResolutionStart and ResolutionComplete") {
            val checker = resolutionChecker()
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

            val ordering = checker.violations.filter { it.check == "resolution_transfer_ordering" }
            ordering.shouldNotBeEmpty()
            ordering.size shouldBe 1
        }

        test("resolution_transfer_ordering records two violations when stack Resolve ZTs precede ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "Resolve", affectedId = 100),
                            annotation(2, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(3, "Resolve", affectedId = 200),
                            annotation(4, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.size shouldBe 2
        }

        test("no resolution_transfer_ordering violation when stack Resolve ZT follows ResolutionComplete") {
            val checker = resolutionChecker()
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

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("no resolution_transfer_ordering violation when RS and RC are absent") {
            val checker = resolutionChecker()
            val g = gsm(gsId = 1, annotations = listOf(zoneTransferAnnotation(1, "Resolve")))

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("no resolution_transfer_ordering violation when non-Resolve ZT sits before ResolutionComplete") {
            val checker = resolutionChecker()
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

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("no resolution_transfer_ordering violation when multiple stack Resolve ZTs follow ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            annotation(2, AnnotationType.ResolutionComplete),
                            zoneTransferAnnotation(3, "Resolve", affectedId = 100),
                            zoneTransferAnnotation(4, "Resolve", affectedId = 200),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test(
            "no resolution_transfer_ordering violation when non-stack Resolve ZT sits inside ResolutionStart and ResolutionComplete",
        ) {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(2, "Resolve", srcZoneId = ZoneIds.P1_LIBRARY),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("resolution_transfer_ordering violation when non-stack Resolve ZT lands before ResolutionStart") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "Resolve", srcZoneId = ZoneIds.P1_LIBRARY),
                            annotation(2, AnnotationType.ResolutionStart),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))
            checker.violations.filter { it.check == "resolution_transfer_ordering" }.size shouldBe 1
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

        test("annotation_ref treats open ability lifecycles as known ids") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 116, affectorId = 1)))
            val g2 =
                gsm(
                    gsId = 2,
                    annotations =
                        listOf(
                            resolutionStartAnnotation(id = 1, abilityIid = 116),
                            aidAnnotation(id = 2, abilityIid = 116, affectorId = 1),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            checker.violations.filter { it.check == "annotation_ref" }.shouldBeEmpty()
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
