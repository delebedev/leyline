package leyline.mechanics.cost

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest

class UntapCostLifecycleTest :
    SessionTest({
        test("exact untap cost delegates through Forge with stun-aware candidates") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Halo Fountain;Grizzly Bears|Tapped|Counters:STUN=1;Walking Corpse|Tapped;Plains
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Untap cost with stun",
                validating = true,
            )

            activateAbility("Halo Fountain", abilityIndex = 0).shouldBeTrue()
            val battlefield = human.getZone(ZoneType.Battlefield).cards
            val bear = battlefield.single { it.name == "Grizzly Bears" }
            val corpse = battlefield.single { it.name == "Walking Corpse" }
            val pending =
                harness.bridge
                    .seat(SeatId(1))
                    .prompt
                    .getPendingPrompt()
                    .shouldNotBeNull()
            val bearChoice = pending.request.candidateRefs.single { it.entityId == bear.id }
            val corpseChoice = pending.request.candidateRefs.single { it.entityId == corpse.id }

            assertSoftly {
                pending.request.semantic shouldBe PromptSemantic.Generic
                pending.request.min shouldBe 0
                pending.request.max shouldBe 1
                pending.request.candidateRefs shouldHaveSize 2
                bearChoice.entityId shouldBe bear.id
                bear.getCounters(CounterEnumType.STUN) shouldBe 1
            }

            harness.bridge
                .seat(SeatId(1))
                .prompt
                .submitResponse(pending.promptId, listOf(corpseChoice.index))
            harness.bridge.awaitPriority()

            assertSoftly {
                bear.isTapped.shouldBeTrue()
                bear.getCounters(CounterEnumType.STUN) shouldBe 1
                corpse.isTapped shouldBe false
                battlefield.single { it.name == "Halo Fountain" }.isTapped.shouldBeTrue()
            }
        }
    })
