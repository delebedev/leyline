package leyline.mechanics.imprint

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ImprintProjectionTest :
    BoardTest({
        test("resync preserves only actual imprint membership and clearing it retires the display") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Imprint display
                    Goal:Win
                    Turns:3
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Isochron Scepter
                    humanexile=Shock;Lightning Strike
                    humanlibrary=Island
                    ailibrary=Island
                    """.trimIndent(),
                )
            val scepter = board.human.battlefield.card("Isochron Scepter")
            val shock = board.human.exile.card("Shock")
            scepter.addImprintedCard(shock)
            val full = handshakeFull(board.game, board.bridge, 21)
            val display = full.persistentAnnotationsList.single { AnnotationType.DisplayCardUnderCard in it.typeList }
            assertSoftly {
                display.affectorId shouldBe board.bridge.instanceId(scepter)
                display.affectedIdsList shouldBe listOf(board.bridge.instanceId(shock))
                display.detailsList.map { it.key } shouldBe listOf("Disable")
                handshakeFull(board.game, board.bridge, 22)
                    .persistentAnnotationsList
                    .single { AnnotationType.DisplayCardUnderCard in it.typeList }
                    .id shouldBe display.id
            }

            val diff = board.snapshotDiff { scepter.clearImprintedCards() }
            assertSoftly {
                diff.diffDeletedPersistentAnnotationIdsList shouldContain display.id
                board.human.exile.card("Shock") shouldBe shock
                handshakeFull(board.game, board.bridge, 24)
                    .persistentAnnotationsList
                    .filter { AnnotationType.DisplayCardUnderCard in it.typeList } shouldBe emptyList()
            }
        }
    })
