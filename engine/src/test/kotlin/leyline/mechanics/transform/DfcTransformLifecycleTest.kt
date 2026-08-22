package leyline.mechanics.transform

import forge.card.CardStateName
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.*
import leyline.testkit.SessionTest

class DfcTransformLifecycleTest :
    SessionTest({

        session(
            "activated transform resolves through MatchSession",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp
                aibattlefield=Runeclaw Bear
                """.trimIndent(),
        ) {
            activateAbility("Concealing Curtains", 0).shouldBeTrue()
            passUntilResolved()

            val transformed = human.battlefield.card("Revealing Eye")
            assertSoftly {
                transformed.isBackSide shouldBe true
                transformed.currentStateName shouldBe CardStateName.Backside
                transformed.name shouldBe "Revealing Eye"
            }
        }
    })
