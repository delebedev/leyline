package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInExileOf
import leyline.tooling.headless.HeadlessResponseMode

class TaintedPactTest :
    SessionTest({
        for (answers in listOf(listOf(true), listOf(false, false, true), listOf(false, false, false))) {
            session(
                "Tainted Pact preserves optional take decisions $answers",
                fullControl = true,
                responseMode = HeadlessResponseMode.PolicyVisible,
                puzzleFile = "data/puzzles/tainted-pact-choice.pzl",
            ) {
                holdNextOptionalAction()
                castSpellByName("Tainted Pact") shouldBe true
                passUntil(maxPasses = 2) { allMessages.any { it.hasOptionalActionMessage() } }
                allMessages.count { it.hasOptionalActionMessage() } shouldBe 1
                for ((index, answer) in answers.withIndex()) {
                    val count = allMessages.count { it.hasOptionalActionMessage() }
                    if (!answer) holdNextOptionalAction()
                    respondToOptionalAction(answer)
                    if (index < answers.lastIndex) allMessages.count { it.hasOptionalActionMessage() } shouldBe count + 1
                }
                passUntilResolved()
                if (answers.last()) {
                    human.hand.card(if (answers.size == 1) "Forest" else "Mountain").name shouldBe
                        if (answers.size == 1) "Forest" else "Mountain"
                } else {
                    assertSoftly {
                        "Forest" should beInExileOf(human, count = 2)
                        "Island" should beInExileOf(human)
                        "Mountain" should beInExileOf(human)
                        allMessages.count { it.hasOptionalActionMessage() } shouldBe 3
                    }
                }
                castSpellByName("Ornithopter") shouldBe true
                passUntilResolved()
                human.battlefield.card("Ornithopter").name shouldBe "Ornithopter"
            }
        }
    })
