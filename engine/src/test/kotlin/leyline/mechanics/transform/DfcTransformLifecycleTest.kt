package leyline.mechanics.transform

import forge.card.CardStateName
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
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
            val curtains =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Concealing Curtains" }

            activateAbility("Concealing Curtains", 0).shouldBeTrue()
            passUntilResolved()

            assertSoftly {
                curtains.isBackSide shouldBe true
                curtains.currentStateName shouldBe CardStateName.Backside
                curtains.name shouldBe "Revealing Eye"
            }
        }
    })
