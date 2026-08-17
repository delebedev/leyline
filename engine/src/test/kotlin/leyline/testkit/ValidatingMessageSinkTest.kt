package leyline.testkit

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.game.codes.DetailKeys
import leyline.infra.ListMessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Unit tests for [ValidatingMessageSink].
 *
 * Hand-built proto messages that trigger each invariant violation.
 * No engine boot required.
 */
class ValidatingMessageSinkTest :
    FunSpec({

        tags(UnitTag)

        // --- Helpers ---

        fun gre(
            msgId: Int = 1,
            gsId: Int = 0,
            configure: GREToClientMessage.Builder.() -> Unit = {},
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .apply(configure)
                .build()

        fun gsm(
            gsId: Int,
            prevGsId: Int = 0,
            type: GameStateType = GameStateType.Diff,
            pendingMessageCount: Int = 0,
            update: GameStateUpdate = GameStateUpdate.None_a0c7,
            annotations: List<AnnotationInfo> = emptyList(),
        ): GameStateMessage =
            GameStateMessage
                .newBuilder()
                .setGameStateId(gsId)
                .setPrevGameStateId(prevGsId)
                .setType(type)
                .setPendingMessageCount(pendingMessageCount)
                .setUpdate(update)
                .addAllAnnotations(annotations)
                .build()

        fun annotation(
            id: Int,
            type: AnnotationType = AnnotationType.None_af5a,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(type)
                .build()

        fun intDetail(
            key: String,
            value: Int,
        ): KeyValuePairInfo =
            KeyValuePairInfo
                .newBuilder()
                .setKey(key)
                .setType(KeyValuePairValueType.Int32)
                .addValueInt32(value)
                .build()

        fun lenientSink() = ValidatingMessageSink(strict = false)

        fun strictSink() = ValidatingMessageSink(strict = true)

        fun selectedSink(selection: InvariantSelection) = ValidatingMessageSink(strict = false, selection = selection)

        fun diagnosticSink(
            reason: String,
            vararg checks: InvariantCheck,
        ) = selectedSink(InvariantSelection.only(reason, *checks))

        // --- Positive: clean stream ---

        test("Clean message stream produces no violations") {
            val sink = lenientSink()

            val gsm1 = gsm(gsId = 1, type = GameStateType.Full, annotations = listOf(annotation(1)))
            val gsm2 = gsm(gsId = 2, prevGsId = 1, annotations = listOf(annotation(1), annotation(2)))
            val gsm3 = gsm(gsId = 3, prevGsId = 2)

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))
            sink.send(listOf(greMessage(msgId = 3, gsm = gsm3)))

            assertSoftly {
                sink.violations.shouldBeEmpty()
                sink.messages.size shouldBe 3
                sink.assertClean()
            }
        }

        // --- gsId monotonicity ---

        test("Detects non-monotonic gsId") {
            val sink = lenientSink()

            val gsm1 = gsm(gsId = 5, type = GameStateType.Full)
            val gsm2 = gsm(gsId = 3) // violation: 3 < 5

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))

            sink.violations shouldBe listOf("gsId=3 gsId not monotonic: got 3, expected > 5")
        }

        test("Lenient validation records violations by check") {
            val sink = lenientSink()

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm(gsId = 5, type = GameStateType.Full))))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm(gsId = 3))))

            sink.violationsByCheck[InvariantCheck.GsIdMonotonicity.id] shouldBe 1
        }

        test("Annotation references may target same-GSM ObjectIdChanged new ids") {
            val sink = selectedSink(InvariantSelection.only("annotation refs", InvariantCheck.AnnotationReferences))
            val oic =
                annotation(1, AnnotationType.ObjectIdChanged)
                    .toBuilder()
                    .addDetails(intDetail(DetailKeys.ORIG_ID, 100))
                    .addDetails(intDetail(DetailKeys.NEW_ID, 220))
                    .build()
            val zt = annotation(2, AnnotationType.ZoneTransfer_af5a).toBuilder().addAffectedIds(220).build()

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm(gsId = 1, annotations = listOf(oic, zt)))))

            sink.violationsByCheck[InvariantCheck.AnnotationReferences.id] shouldBe null
        }

        test("Annotation references may target same-GSM deleted ids") {
            val sink = selectedSink(InvariantSelection.only("annotation refs", InvariantCheck.AnnotationReferences))
            val deletedToken =
                annotation(1, AnnotationType.TokenDeleted)
                    .toBuilder()
                    .setAffectorId(242)
                    .addAffectedIds(242)
                    .build()
            val gsm =
                gsm(gsId = 1, annotations = listOf(deletedToken))
                    .toBuilder()
                    .addDiffDeletedInstanceIds(242)
                    .build()

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm)))

            sink.violationsByCheck[InvariantCheck.AnnotationReferences.id] shouldBe null
        }

        test("gsId monotonicity throws in strict mode") {
            val sink = strictSink()
            val gsm1 = gsm(gsId = 5, type = GameStateType.Full)

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))

            shouldThrow<AssertionError> {
                sink.send(listOf(greMessage(msgId = 2, gsm = gsm(gsId = 3))))
            }.message shouldContain "gsId not monotonic"
        }

        // --- prevGsId validity ---

        test("Detects prevGsId referencing unknown gsId") {
            val sink = diagnosticSink("prev gsId diagnostic", InvariantCheck.GsIdPrevKnown)

            val gsm1 = gsm(gsId = 1, type = GameStateType.Full)
            val gsm2 = gsm(gsId = 2, prevGsId = 99) // violation: 99 never seen

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))

            sink.violationsByCheck[InvariantCheck.GsIdPrevKnown.id] shouldBe 1
            sink.violations.shouldExist { "prevGsId 99 not in known set" in it }
        }

        test("Selection can relax one protocol fact while preserving others") {
            val sink =
                selectedSink(
                    InvariantSelection.protocolFactsExcept(
                        "exercise partial validation selection",
                        InvariantCheck.GsIdNoSelfRef,
                    ),
                )

            val gsm1 = gsm(gsId = 1, type = GameStateType.Full)
            val gsm2 = gsm(gsId = 2, prevGsId = 2)
            val gsm3 = gsm(gsId = 1, prevGsId = 2)

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))
            sink.send(listOf(greMessage(msgId = 3, gsm = gsm3)))

            sink.violations.none { "Self-referential gsId" in it } shouldBe true
            sink.violations.shouldExist { "gsId not monotonic" in it }
        }

        // --- No self-referential gsId ---

        test("Detects self-referential gsId == prevGsId") {
            val sink = lenientSink()

            // Seed gsId=5 first so monotonicity passes
            val seed = gsm(gsId = 5, type = GameStateType.Full)
            sink.send(listOf(greMessage(msgId = 1, gsm = seed)))

            val bad = gsm(gsId = 7, prevGsId = 7) // violation: self-ref
            sink.send(listOf(greMessage(msgId = 2, gsm = bad)))

            sink.violationsByCheck[InvariantCheck.GsIdNoSelfRef.id] shouldBe 1
            sink.violations.shouldExist { "Self-referential gsId" in it }
        }

        // --- msgId monotonicity ---

        test("Detects non-monotonic msgId") {
            val sink = diagnosticSink("msgId diagnostic", InvariantCheck.MsgIdMonotonicity)

            val msg1 = gre(msgId = 5)
            val msg2 = gre(msgId = 3) // violation: 3 < 5

            sink.send(listOf(msg1))
            sink.send(listOf(msg2))

            sink.violationsByCheck[InvariantCheck.MsgIdMonotonicity.id] shouldBe 1
            sink.violations.shouldExist { "msgId not monotonic" in it }
        }

        // --- Annotation ID sequentiality ---

        test("Detects non-sequential annotation IDs") {
            val sink = diagnosticSink("annotation id diagnostic", InvariantCheck.AnnotationSequentiality)

            // IDs must be contiguous: 1,5 has a gap (expected 2 after 1)
            val badGsm =
                gsm(
                    gsId = 1,
                    type = GameStateType.Full,
                    annotations = listOf(annotation(1), annotation(5)),
                )

            sink.send(listOf(greMessage(msgId = 1, gsm = badGsm)))

            sink.violationsByCheck[InvariantCheck.AnnotationSequentiality.id] shouldBe 1
            sink.violations.shouldExist { "Annotation IDs not sequential" in it }
        }

        test("Sequential annotation IDs starting from arbitrary value are OK") {
            val sink = diagnosticSink("annotation id diagnostic", InvariantCheck.AnnotationSequentiality)

            // IDs 50,51,52 — contiguous, just not starting from 1
            val goodGsm =
                gsm(
                    gsId = 1,
                    type = GameStateType.Full,
                    annotations = listOf(annotation(50), annotation(51), annotation(52)),
                )

            sink.send(listOf(greMessage(msgId = 1, gsm = goodGsm)))

            sink.violations.shouldBeEmpty()
        }

        test("Detects zero annotation ID in mixed-id GSM") {
            val sink = diagnosticSink("annotation id diagnostic", InvariantCheck.AnnotationSequentiality)

            // Mix of assigned and unassigned IDs — id=0 among non-zero triggers violation
            val badGsm =
                gsm(
                    gsId = 1,
                    type = GameStateType.Full,
                    annotations = listOf(annotation(1), annotation(0)), // violation: id=0 mixed with assigned
                )

            sink.send(listOf(greMessage(msgId = 1, gsm = badGsm)))

            sink.violationsByCheck[InvariantCheck.AnnotationSequentiality.id] shouldBe 1
            sink.violations.shouldExist { "id=0" in it }
        }

        // --- Action instanceId consistency ---

        test("Detects action instanceId missing from objects") {
            val sink = diagnosticSink("action iid diagnostic", InvariantCheck.ActionInstanceIds)

            // Send a Full GSM with no objects
            val fullGsm = gsm(gsId = 1, type = GameStateType.Full)
            sink.send(listOf(greMessage(msgId = 1, gsm = fullGsm)))

            // Send AAR referencing instanceId=999 which doesn't exist
            sink.send(
                listOf(
                    actionsMessage(msgId = 2) {
                        addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(999))
                    },
                ),
            )

            sink.violationsByCheck[InvariantCheck.ActionInstanceIds.id] shouldBe 1
            sink.violations.shouldExist { "Action instanceIds missing" in it }
        }

        // --- Zone-object consistency ---

        test("Detects zone object missing from objects map") {
            val sink = diagnosticSink("zone object diagnostic", InvariantCheck.ZoneObjects)

            // Full GSM with a visible zone referencing instanceId=42, but no matching object
            sink.send(
                listOf(
                    greMessage(msgId = 1, gsId = 1) {
                        setType(GameStateType.Full)
                        addZones(
                            ZoneInfo
                                .newBuilder()
                                .setZoneId(1)
                                .setType(ZoneType.Battlefield)
                                .setVisibility(Visibility.Public)
                                .addObjectInstanceIds(42),
                        )
                    },
                ),
            )

            sink.violationsByCheck[InvariantCheck.ZoneObjects.id] shouldBe 1
            sink.violations.shouldExist { "Zone objects missing" in it }
        }

        test("Hidden/Private/Limbo zones are skipped for zone-object check") {
            val sink = diagnosticSink("zone object diagnostic", InvariantCheck.ZoneObjects)

            sink.send(
                listOf(
                    greMessage(msgId = 1, gsId = 1) {
                        setType(GameStateType.Full)
                        addZones(
                            ZoneInfo
                                .newBuilder()
                                .setZoneId(1)
                                .setType(ZoneType.Library)
                                .setVisibility(Visibility.Hidden)
                                .addObjectInstanceIds(100),
                        )
                        addZones(
                            ZoneInfo
                                .newBuilder()
                                .setZoneId(2)
                                .setType(ZoneType.Hand)
                                .setVisibility(Visibility.Private)
                                .addObjectInstanceIds(200),
                        )
                        addZones(
                            ZoneInfo
                                .newBuilder()
                                .setZoneId(3)
                                .setType(ZoneType.Limbo)
                                .setVisibility(Visibility.Public)
                                .addObjectInstanceIds(300),
                        )
                    },
                ),
            )

            sink.violations.shouldBeEmpty()
        }

        // No pendingMessageCount coverage here on purpose: the corresponding
        // InvariantChecker rule is disabled, so a test would assert against a
        // check that never runs. Add both together, not a test alone.

        // --- Delegation ---

        test("Messages are captured in inner sink") {
            val inner = ListMessageSink()
            val sink = ValidatingMessageSink(inner, strict = false)

            val msg = gre(msgId = 1)
            sink.send(listOf(msg))

            assertSoftly {
                inner.messages.size shouldBe 1
                inner.messages[0] shouldBe msg
                sink.messages.size shouldBe 1
            }
        }

        test("sendRaw delegates to inner sink") {
            val sink = lenientSink()
            val raw = MatchServiceToClientMessage.newBuilder().setRequestId(42).build()

            sink.sendRaw(raw)

            sink.rawMessages.size shouldBe 1
            sink.rawMessages[0].requestId shouldBe 42
        }

        test("clear delegates to inner sink") {
            val sink = lenientSink()
            sink.send(listOf(gre(msgId = 1)))
            sink.sendRaw(MatchServiceToClientMessage.getDefaultInstance())

            sink.clear()

            sink.messages.shouldBeEmpty()
            sink.rawMessages.shouldBeEmpty()
        }

        // --- assertClean ---

        test("assertClean throws when violations exist") {
            val sink = lenientSink()

            // Force a violation
            sink.send(listOf(greMessage(msgId = 1, gsm = gsm(gsId = 5))))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm(gsId = 3))))

            shouldThrow<AssertionError> {
                sink.assertClean()
            }.message!!.lowercase() shouldContain "violation"
        }

        // --- annotation ordering ---

        test("Detects ObjectIdChanged after annotation referencing its newId") {
            val sink = diagnosticSink("annotation ordering diagnostic", InvariantCheck.AnnotationOrdering)

            val zt =
                AnnotationInfo
                    .newBuilder()
                    .setId(1)
                    .addType(AnnotationType.ZoneTransfer_af5a)
                    .addAffectedIds(200) // references newId=200
                    .build()
            val oic =
                AnnotationInfo
                    .newBuilder()
                    .setId(2)
                    .addType(AnnotationType.ObjectIdChanged)
                    .addAffectedIds(100) // origId
                    .addDetails(
                        KeyValuePairInfo
                            .newBuilder()
                            .setKey("new_id")
                            .addValueInt32(200),
                    ).build()

            // Wrong order: ZT before OIC
            val badGsm = gsm(gsId = 1, type = GameStateType.Full, annotations = listOf(zt, oic))
            sink.send(listOf(greMessage(msgId = 1, gsm = badGsm)))

            sink.violationsByCheck[InvariantCheck.AnnotationOrdering.id] shouldBe 2
            sink.violations.shouldExist { "annotation ordering violation" in it }
        }

        test("No violation when ObjectIdChanged precedes referencing annotation") {
            val sink = diagnosticSink("annotation ordering diagnostic", InvariantCheck.AnnotationOrdering)

            val oic =
                AnnotationInfo
                    .newBuilder()
                    .setId(1)
                    .addType(AnnotationType.ObjectIdChanged)
                    .addAffectedIds(100)
                    .addDetails(
                        KeyValuePairInfo
                            .newBuilder()
                            .setKey("new_id")
                            .addValueInt32(200),
                    ).build()
            val zt =
                AnnotationInfo
                    .newBuilder()
                    .setId(2)
                    .addType(AnnotationType.ZoneTransfer_af5a)
                    .addAffectedIds(200)
                    .build()

            // Include game objects so referential integrity check passes
            val obj200 = GameObjectInfo.newBuilder().setInstanceId(200).build()
            val goodGsm =
                GameStateMessage
                    .newBuilder()
                    .setGameStateId(1)
                    .setType(GameStateType.Full)
                    .addAllAnnotations(listOf(oic, zt))
                    .addGameObjects(obj200)
                    .build()
            sink.send(listOf(greMessage(msgId = 1, gsm = goodGsm)))

            sink.violations.shouldBeEmpty()
        }

        // --- seedFull ---

        test("seedFull populates gsId tracking so subsequent diffs validate correctly") {
            val sink = lenientSink()

            val fullGsm = gsm(gsId = 10, type = GameStateType.Full)
            sink.seedFull(fullGsm)

            // Diff referencing prevGsId=10 should be fine
            val diffGsm = gsm(gsId = 11, prevGsId = 10)
            sink.send(listOf(greMessage(msgId = 1, gsm = diffGsm)))

            sink.violations.shouldBeEmpty()
        }
    })
