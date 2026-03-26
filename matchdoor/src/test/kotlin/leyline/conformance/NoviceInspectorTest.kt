package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.GameBootstrap
import leyline.bridge.SeatId

/**
 * Novice Inspector — investigate + Clue token + sac-for-draw.
 *
 * Cast {W} 1/2 creature → ETB creates Clue artifact token →
 * activate Clue ({2}, sacrifice: draw a card) → verify draw + Clue gone.
 */
class NoviceInspectorTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()

            val repo = TestCardRegistry.repo
            TestCardRegistry.ensureCardRegistered("Novice Inspector")
            TestCardRegistry.ensureCardRegistered("Runeclaw Bear")

            // Wire Clue token grpId mapping: Novice Inspector → Clue token
            val clueTokenGrpId = 300002
            val inspectorGrpId = repo.findGrpIdByName("Novice Inspector")!!
            repo.register(clueTokenGrpId, "Clue")
            val inspectorData = repo.findByGrpId(inspectorGrpId)!!
            repo.registerData(
                inspectorData.copy(tokenGrpIds = mapOf(0 to clueTokenGrpId)),
                "Novice Inspector",
            )
        }

        val puzzleText = """
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
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)
            val human = h.bridge.getPlayer(SeatId(1))!!
            // 1. Cast Novice Inspector
            h.castSpellByName("Novice Inspector").shouldBeTrue()

            // 2. Pass until Clue token appears (spell resolve + ETB trigger resolve)
            repeat(15) {
                if (human.getZone(ZoneType.Battlefield).cards.toList()
                        .any { it.name.contains("Clue", ignoreCase = true) }
                ) {
                    return@repeat
                }
                h.passPriority()
            }

            val bfCards = human.getZone(ZoneType.Battlefield).cards.toList()
            bfCards.map { it.name } shouldContain "Novice Inspector"
            val clueCard = bfCards.first { it.name.contains("Clue", ignoreCase = true) }
            clueCard.isToken.shouldBeTrue()

            // 3. Activate Clue — {2}, sacrifice: draw a card
            val libBefore = human.getZone(ZoneType.Library).cards.toList().size
            h.activateAbility(clueCard.name).shouldBeTrue()

            // 4. Pass until Clue is gone (cost paid + ability resolves)
            repeat(15) {
                if (human.getZone(ZoneType.Battlefield).cards.toList()
                        .none { it.name.contains("Clue", ignoreCase = true) }
                ) {
                    return@repeat
                }
                h.passPriority()
            }

            // Clue sacrificed — no longer on battlefield
            human.getZone(ZoneType.Battlefield).cards.toList()
                .none { it.name.contains("Clue", ignoreCase = true) }.shouldBeTrue()

            // Draw happened — library shrank by 1
            human.getZone(ZoneType.Library).cards.toList().size shouldBe libBefore - 1
        }
    })
