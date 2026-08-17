package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after

class BoundedTargetingInteractionTest :
    SessionTest({

        session(
            "up-to-two targeting preserves the zero minimum",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Eddymurk Crab
                humanbattlefield=Island;Island;Island;Island;Island;Island;Island;Grizzly Bears
                humanlibrary=Island
                aibattlefield=Coral Merfolk
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            val castMessages = after { castSpellByName("Eddymurk Crab").shouldBeTrue() }.messages
            val targetPrompt = castMessages.firstOrNull { it.hasSelectTargetsReq() }
            targetPrompt.shouldNotBeNull()

            assertSoftly {
                targetPrompt.selectTargetsReq.targetsList
                    .single()
                    .minTargets shouldBe 0
                targetPrompt.selectTargetsReq.targetsList
                    .single()
                    .maxTargets shouldBe 2
            }
        }
    })
