package leyline.conformance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
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
            GREToClientMessage.newBuilder()
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
            GameStateMessage.newBuilder()
                .setGameStateId(gsId)
                .setPrevGameStateId(prevGsId)
                .setType(type)
                .setPendingMessageCount(pendingMessageCount)
                .setUpdate(update)
                .addAllAnnotations(annotations)
                .build()

        fun annotation(id: Int, type: AnnotationType = AnnotationType.None_af5a): AnnotationInfo =
            AnnotationInfo.newBuilder()
                .setId(id)
                .addType(type)
                .build()

        fun lenientSink() = ValidatingMessageSink(strict = false)
        fun strictSink() = ValidatingMessageSink(strict = true)

        // --- Positive: clean stream ---

        test("Clean message stream produces no violations") {
            val sink = lenientSink()

            val gsm1 = gsm(gsId = 1, type = GameStateType.Full, annotations = listOf(annotation(1)))
            val gsm2 = gsm(gsId = 2, prevGsId = 1, annotations = listOf(annotation(1), annotation(2)))
            val gsm3 = gsm(gsId = 3, prevGsId = 2)

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))
            sink.send(listOf(greMessage(msgId = 3, gsm = gsm3)))

            sink.violations.shouldBeEmpty()
            sink.messages.size shouldBe 3
            sink.assertClean()
        }

        // --- gsId monotonicity ---

        test("Detects non-monotonic gsId") {
            val sink = lenientSink()

            val gsm1 = gsm(gsId = 5, type = GameStateType.Full)
            val gsm2 = gsm(gsId = 3) // violation: 3 < 5

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))

            sink.violations.shouldExist { "gsId not monotonic" in it }
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
            val sink = lenientSink()

            val gsm1 = gsm(gsId = 1, type = GameStateType.Full)
            val gsm2 = gsm(gsId = 2, prevGsId = 99) // violation: 99 never seen

            sink.send(listOf(greMessage(msgId = 1, gsm = gsm1)))
            sink.send(listOf(greMessage(msgId = 2, gsm = gsm2)))

            sink.violations.shouldExist { "prevGsId 99 not in known set" in it }
        }

        // --- No self-referential gsId ---

        test("Detects self-referential gsId == prevGsId") {
            val sink = lenientSink()

            // Seed gsId=5 first so monotonicity passes
            val seed = gsm(gsId = 5, type = GameStateType.Full)
            sink.send(listOf(greMessage(msgId = 1, gsm = seed)))

            val bad = gsm(gsId = 7, prevGsId = 7) // violation: self-ref
            sink.send(listOf(greMessage(msgId = 2, gsm = bad)))

            sink.violations.shouldExist { "Self-referential gsId" in it }
        }

        // --- msgId monotonicity ---

        test("Detects non-monotonic msgId") {
            val sink = lenientSink()

            val msg1 = gre(msgId = 5)
            val msg2 = gre(msgId = 3) // violation: 3 < 5

            sink.send(listOf(msg1))
            sink.send(listOf(msg2))

            sink.violations.shouldExist { "msgId not monotonic" in it }
        }

        // --- Annotation ID sequentiality ---

        test("Detects non-sequential annotation IDs") {
            val sink = lenientSink()

            // IDs must be contiguous: 1,5 has a gap (expected 2 after 1)
            val badGsm = gsm(
                gsId = 1,
                type = GameStateType.Full,
                annotations = listOf(annotation(1), annotation(5)),
            )

            sink.send(listOf(greMessage(msgId = 1, gsm = badGsm)))

            sink.violations.shouldExist { "Annotation IDs not sequential" in it }
        }

        test("Sequential annotation IDs starting from arbitrary value are OK") {
            val sink = lenientSink()

            // IDs 50,51,52 — contiguous, just not starting from 1
            val goodGsm = gsm(
                gsId = 1,
                type = GameStateType.Full,
                annotations = listOf(annotation(50), annotation(51), annotation(52)),
            )

            sink.send(listOf(greMessage(msgId = 1, gsm = goodGsm)))

            sink.violations.shouldBeEmpty()
        }

        test("Detects zero annotation ID in mixed-id GSM") {
            val sink = lenientSink()

            // Mix of assigned and unassigned IDs — id=0 among non-zero triggers violation
            val badGsm = gsm(
                gsId = 1,
                type = GameStateType.Full,
                annotations = listOf(annotation(1), annotation(0)), // violation: id=0 mixed with assigned
            )

            sink.send(listOf(greMessage(msgId = 1, gsm = badGsm)))

            sink.violations.shouldExist { "id=0" in it }
        }

        // --- Action instanceId consistency ---

        test("Detects action instanceId missing from objects") {
            val sink = lenientSink()

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

            sink.violations.shouldExist { "Action instanceIds missing" in it }
        }

        // --- Zone-object consistency ---

        test("Detects zone object missing from objects map") {
            val sink = lenientSink()

            // Full GSM with a visible zone referencing instanceId=42, but no matching object
            sink.send(
                listOf(
                    greMessage(msgId = 1, gsId = 1) {
                        setType(GameStateType.Full)
                        addZones(
                            ZoneInfo.newBuilder()
                                .setZoneId(1)
                                .setType(ZoneType.Battlefield)
                                .setVisibility(Visibility.Public)
                                .addObjectInstanceIds(42),
                        )
                    },
                ),
            )

            sink.violations.shouldExist { "Zone objects missing" in it }
        }

        test("Hidden/Private/Limbo zones are skipped for zone-object check") {
            val sink = lenientSink()

            sink.send(
                listOf(
                    greMessage(msgId = 1, gsId = 1) {
                        setType(GameStateType.Full)
                        addZones(
                            ZoneInfo.newBuilder()
                                .setZoneId(1).setType(ZoneType.Library).setVisibility(Visibility.Hidden)
                                .addObjectInstanceIds(100),
                        )
                        addZones(
                            ZoneInfo.newBuilder()
                                .setZoneId(2).setType(ZoneType.Hand).setVisibility(Visibility.Private)
                                .addObjectInstanceIds(200),
                        )
                        addZones(
                            ZoneInfo.newBuilder()
                                .setZoneId(3).setType(ZoneType.Limbo).setVisibility(Visibility.Public)
                                .addObjectInstanceIds(300),
                        )
                    },
                ),
            )

            sink.violations.shouldBeEmpty()
        }

        // --- pendingMessageCount contract ---

        // TODO: pendingMessageCount check is commented out in InvariantChecker (too strict).
        // Re-enable this test when the diff pipeline guarantees correct pending counts.
        // xtest("Detects SendAndRecord arriving before pending countdown completes") { ... }

        // --- Delegation ---

        test("Messages are captured in inner sink") {
            val inner = ListMessageSink()
            val sink = ValidatingMessageSink(inner, strict = false)

            val msg = gre(msgId = 1)
            sink.send(listOf(msg))

            inner.messages.size shouldBe 1
            inner.messages[0] shouldBe msg
            sink.messages.size shouldBe 1
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
            sink.send(listOf(gre(msgId = 5)))
            sink.send(listOf(gre(msgId = 3)))

            shouldThrow<AssertionError> {
                sink.assertClean()
            }.message!!.lowercase() shouldContain "violation"
        }

        // --- Batch-aware annotation ref check ---

        test("Batch send: annotation referencing object from earlier message in same batch is valid") {
            val sink = lenientSink()

            // Seed with a Full GSM so the checker has baseline state
            val fullGsm = gsm(gsId = 1, type = GameStateType.Full)
            sink.send(listOf(greMessage(msgId = 1, gsm = fullGsm)))

            // Batch: message 1 introduces object 42 via a Diff, message 2 has
            // an annotation referencing 42 as affectedId. Within a single send()
            // call, the checker should defer annotation ref checks until all
            // messages are accumulated — so 42 is known when the check runs.
            val diff1 = GameStateMessage.newBuilder()
                .setGameStateId(2)
                .setPrevGameStateId(1)
                .setType(GameStateType.Diff)
                .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(42))
                .build()
            val diff2 = GameStateMessage.newBuilder()
                .setGameStateId(3)
                .setPrevGameStateId(2)
                .setType(GameStateType.Diff)
                .addAnnotations(
                    AnnotationInfo.newBuilder()
                        .setId(1)
                        .addType(AnnotationType.ZoneTransfer_af5a)
                        .addAffectedIds(42),
                )
                .build()

            sink.send(listOf(greMessage(msgId = 2, gsm = diff1), greMessage(msgId = 3, gsm = diff2)))

            sink.violations.shouldBeEmpty()
        }

        test("Annotation referencing previously-seen-then-deleted object is valid") {
            val sink = lenientSink()

            // Object 42 appears in Full GSM, then gets deleted in a Diff.
            // An annotation in the same Diff references 42 — valid because
            // everSeenInstanceIds tracks all IDs across the checker's lifetime.
            val fullGsm = GameStateMessage.newBuilder()
                .setGameStateId(1)
                .setType(GameStateType.Full)
                .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(42))
                .build()
            sink.send(listOf(greMessage(msgId = 1, gsm = fullGsm)))

            val diff = GameStateMessage.newBuilder()
                .setGameStateId(2)
                .setPrevGameStateId(1)
                .setType(GameStateType.Diff)
                .addDiffDeletedInstanceIds(42)
                .addAnnotations(
                    AnnotationInfo.newBuilder()
                        .setId(1)
                        .addType(AnnotationType.ZoneTransfer_af5a)
                        .addAffectedIds(42),
                )
                .build()

            sink.send(listOf(greMessage(msgId = 2, gsm = diff)))

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
