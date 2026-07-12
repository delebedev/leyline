package leyline.mechanics.cost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest

class OrdinaryTapCostLifecycleTest :
    SessionTest({
        test("ordinary exact-count tap cost delegates through the shared Forge visitor") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Goldfury Strider;Grizzly Bears;Walking Corpse
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Ordinary tap cost",
                validating = true,
            )

            activateAbility("Goldfury Strider").shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            val bearIid = human.battlefield.iid("Grizzly Bears")
            selectTargets(listOf(bearIid))

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
                pending.request.max shouldBe 2
                pending.request.candidateRefs shouldHaveSize 3
            }

            harness.bridge
                .seat(SeatId(1))
                .prompt
                .submitResponse(pending.promptId, listOf(bearChoice.index, corpseChoice.index))
            harness.bridge.awaitPriority()

            assertSoftly {
                battlefield.single { it.name == "Goldfury Strider" }.isTapped shouldBe false
                battlefield.single { it.name == "Grizzly Bears" }.isTapped.shouldBeTrue()
                battlefield.single { it.name == "Walking Corpse" }.isTapped.shouldBeTrue()
            }
        }
    })
