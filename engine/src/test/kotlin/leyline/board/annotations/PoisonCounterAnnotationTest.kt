package leyline.board.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class PoisonCounterAnnotationTest :
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
                counterAdded.detailInt("counter_type") shouldBe 3
                counterAdded.detailInt("transaction_amount") shouldBe 2

                counterState.affectedIdsList shouldContain 1
                counterState.detailInt("counter_type") shouldBe 3
                counterState.detailInt("count") shouldBe 2
            }
        }
    })
