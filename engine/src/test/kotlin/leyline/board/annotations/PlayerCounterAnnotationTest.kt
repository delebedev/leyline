package leyline.board.annotations

import com.google.common.collect.HashMultiset
import forge.game.Game
import forge.game.GameState
import forge.game.card.CounterEnumType
import forge.game.card.CounterType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CounterType as ProtoCounterType

class PlayerCounterAnnotationTest :
    BoardTest({
        test("poison counters emit player CounterAdded and persistent Counter annotations") {
            val board = startWithBoard { _, _, _ -> }
            val human = board.bridge.getPlayer(SeatId(1))!!
            val ai = board.bridge.getPlayer(SeatId(2))!!

            val gsm = board.snapshotDiff { human.setPoisonCounters(2, ai) }
            val counterAdded = gsm.annotationsList.single { AnnotationType.CounterAdded in it.typeList }
            val counterState = gsm.persistentAnnotationsList.single { AnnotationType.Counter_803b in it.typeList }

            assertSoftly {
                counterAdded.affectedIdsList shouldBe listOf(1)
                counterAdded.detailInt("counter_type") shouldBe ProtoCounterType.Poison.number
                counterAdded.detailInt("transaction_amount") shouldBe 2

                counterState.affectedIdsList shouldContain 1
                counterState.detailInt("counter_type") shouldBe ProtoCounterType.Poison.number
                counterState.detailInt("count") shouldBe 2
            }
        }
        test("energy totals update and clear through typed player counter events") {
            val board = startWithBoard { _, _, _ -> }
            val energy = CounterEnumType.ENERGY
            val human = board.human
            val added = board.snapshotDiff { human.setCounters(energy, 2, human, true) }
            val add = added.annotationsList.single { AnnotationType.CounterAdded in it.typeList }
            assertSoftly {
                add.affectedIdsList shouldBe listOf(1)
                add.detailInt("counter_type") shouldBe ProtoCounterType.Energy.number
                add.detailInt("transaction_amount") shouldBe 2
            }
            val updated = board.snapshotDiff { human.setCounters(energy, 3, human, true) }
            updated.annotationsList
                .single { AnnotationType.CounterAdded in it.typeList }
                .detailInt("transaction_amount") shouldBe 1
            updated.persistentAnnotationsList
                .single { AnnotationType.Counter_803b in it.typeList }
                .detailInt("count") shouldBe 3
            val cleared = board.snapshotDiff { human.clearCounters() }
            val remove = cleared.annotationsList.single { AnnotationType.CounterRemoved in it.typeList }
            assertSoftly {
                remove.detailInt("counter_type") shouldBe ProtoCounterType.Energy.number
                remove.detailInt("transaction_amount") shouldBe 3
                cleared.persistentAnnotationsList
                    .single { AnnotationType.Counter_803b in it.typeList }
                    .detailInt("count") shouldBe 0
            }
        }

        test("bulk player counter replacement supplies removals and additions once per type") {
            val board = startWithBoard { _, _, _ -> }
            val energy = CounterEnumType.ENERGY
            board.snapshotDiff {
                board.human.setPoisonCounters(2, board.ai)
                board.human.setCounters(energy, 1, board.human, true)
            }
            val replacement = HashMultiset.create<CounterType>().apply { add(energy, 4) }
            val gsm = board.snapshotDiff { board.human.setCounters(replacement) }
            assertSoftly {
                gsm.annotationsList
                    .single { AnnotationType.CounterRemoved in it.typeList }
                    .detailInt("transaction_amount") shouldBe 2
                gsm.annotationsList
                    .single { AnnotationType.CounterAdded in it.typeList }
                    .detailInt("transaction_amount") shouldBe 3
                gsm.persistentAnnotationsList
                    .filter { AnnotationType.Counter_803b in it.typeList }
                    .associate { it.detailInt("counter_type") to it.detailInt("count") } shouldBe
                    mapOf(ProtoCounterType.Poison.number to 0, ProtoCounterType.Energy.number to 4)
            }
            val cleared = board.snapshotDiff { board.human.setCounters(HashMultiset.create()) }
            cleared.annotationsList
                .single { AnnotationType.CounterRemoved in it.typeList }
                .detailInt("transaction_amount") shouldBe 4
        }

        test("puzzle player totals seed every supported counter type with seat identity") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Player counter totals
                    Goal:Survive
                    Turns:2
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humancounters=POISON=3,ENERGY=2
                    aicounters=POISON=5
                    humanlibrary=Island;Island
                    ailibrary=Forest;Forest
                    """.trimIndent(),
                )
            val rows =
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations.values
                    .filter { AnnotationType.Counter_803b in it.typeList }
            rows.associate {
                (it.affectedIdsList.single() to it.detailInt("counter_type")) to it.detailInt("count")
            } shouldBe
                mapOf(
                    (1 to ProtoCounterType.Poison.number) to 3,
                    (1 to ProtoCounterType.Energy.number) to 2,
                    (2 to ProtoCounterType.Poison.number) to 5,
                )
        }

        test("player state restore emits only old to final counter changes") {
            val board = startWithBoard { _, _, _ -> }
            board.snapshotDiff {
                board.human.setPoisonCounters(2, board.ai)
                board.human.setCounters(CounterEnumType.ENERGY, 1, board.human, true)
            }
            val restore =
                object : GameState() {
                    fun applySynchronously(game: Game) = applyGameOnThread(game)
                }.apply {
                    parse(
                        listOf(
                            "ActivePlayer=Human",
                            "ActivePhase=Main1",
                            "HumanLife=20",
                            "AILife=20",
                            "humancounters=POISON=2,ENERGY=4",
                            "humanlibrary=Island;Island",
                            "ailibrary=Forest;Forest",
                        ),
                    )
                }
            val restored = board.snapshotDiff { restore.applySynchronously(board.game) }
            val changes =
                restored.annotationsList.filter {
                    AnnotationType.CounterAdded in it.typeList || AnnotationType.CounterRemoved in it.typeList
                }
            assertSoftly {
                changes.size shouldBe 1
                changes.single().typeList shouldContain AnnotationType.CounterAdded
                changes.single().detailInt("counter_type") shouldBe ProtoCounterType.Energy.number
                changes.single().detailInt("transaction_amount") shouldBe 3
                board.human.counters.count(CounterEnumType.POISON) shouldBe 2
                board.human.counters.count(CounterEnumType.ENERGY) shouldBe 4
                restored.persistentAnnotationsList
                    .single { AnnotationType.Counter_803b in it.typeList }
                    .detailInt("count") shouldBe 4
            }
            val unchanged = board.snapshotDiff { restore.applySynchronously(board.game) }
            unchanged.annotationsList.filter {
                AnnotationType.CounterAdded in it.typeList || AnnotationType.CounterRemoved in it.typeList
            } shouldBe emptyList()
        }
    })
