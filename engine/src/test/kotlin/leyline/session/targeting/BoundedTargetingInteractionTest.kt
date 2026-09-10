package leyline.session.targeting

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
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

        session(
            "Kaya zero-target activation pays loyalty once and resolves",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Kaya, Orzhov Usurper|Counters:LOYALTY=3
                humanlibrary=Plains;Plains;Plains
                ailibrary=Swamp;Swamp;Swamp
                """.trimIndent(),
        ) {
            activateAbility("Kaya, Orzhov Usurper", abilityIndex = 0).shouldBeTrue()
            passUntil(maxPasses = 5) { game().stackZone.size() > 0 }.shouldBeTrue()
            val kaya = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Kaya, Orzhov Usurper" }
            kaya.getCounters(CounterEnumType.LOYALTY) shouldBe 4

            passUntilResolved(maxPasses = 8)

            assertSoftly {
                game().stackZone.isEmpty.shouldBeTrue()
                human.getZone(ZoneType.Graveyard).cards.shouldBeEmpty()
                kaya.getCounters(CounterEnumType.LOYALTY) shouldBe 4
            }
        }
    })
