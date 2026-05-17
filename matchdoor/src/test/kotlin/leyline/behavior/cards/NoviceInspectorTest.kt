package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry

/**
 * Novice Inspector — investigate + Clue token + sac-for-draw.
 *
 * Cast {W} 1/2 creature → ETB creates Clue artifact token →
 * activate Clue ({2}, sacrifice: draw a card) → verify draw + Clue gone.
 */
class NoviceInspectorTest :
    SessionTest({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Novice Inspector")
            TestCardRegistry.ensureCardRegistered("Runeclaw Bear")
        }

        val puzzleText =
            """
            [metadata]
            Name:Novice Inspector Investigate
            Goal:PlaySpecifiedPermanent
            Turns:3
            Difficulty:Easy
            Description:Cast Novice Inspector to investigate (create Clue token), then sacrifice Clue to draw.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Novice Inspector
            humanbattlefield=Plains;Island;Island
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Runeclaw Bear
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        test("cast → ETB creates Clue token → sac Clue draws card") {
            startPuzzleRaw(puzzleText, validating = true)
            // 1. Cast Novice Inspector
            castSpellByName("Novice Inspector").shouldBeTrue()

            // 2. Pass until Clue token appears (spell resolve + ETB trigger resolve)
            passUntil(maxPasses = 15) {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .toList()
                    .any { it.name.contains("Clue", ignoreCase = true) }
            }.shouldBeTrue()

            val bfCards = human.getZone(ZoneType.Battlefield).cards.toList()
            bfCards.map { it.name } shouldContain "Novice Inspector"
            val clueCard = bfCards.first { it.name.contains("Clue", ignoreCase = true) }
            clueCard.isToken.shouldBeTrue()

            // Verify Clue has the sac-for-draw ability registered
            val clueGrpId = harness.bridge.cardRepository.findGrpIdByName("Clue")
            clueGrpId shouldNotBe null
            val clueData = harness.bridge.cardRepository.findByGrpId(clueGrpId!!)
            clueData shouldNotBe null
            clueData!!.abilityIds.any { it.first == 152 }.shouldBeTrue()

            // 3. Activate Clue — {2}, sacrifice: draw a card
            val libBefore =
                human
                    .getZone(ZoneType.Library)
                    .cards
                    .toList()
                    .size
            activateAbility(clueCard.name).shouldBeTrue()

            // 4. Pass until Clue is gone (cost paid + ability resolves)
            passUntil(maxPasses = 15) {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .toList()
                    .none { it.name.contains("Clue", ignoreCase = true) }
            }.shouldBeTrue()

            // Clue sacrificed — no longer on battlefield
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .toList()
                .none { it.name.contains("Clue", ignoreCase = true) }
                .shouldBeTrue()

            // Draw happened — library shrank by 1
            human
                .getZone(ZoneType.Library)
                .cards
                .toList()
                .size shouldBe libBefore - 1
        }
    })
