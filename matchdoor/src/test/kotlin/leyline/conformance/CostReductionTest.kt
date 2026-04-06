package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Static cost reduction — Archmage of Runes + Run Away Together.
 *
 * Archmage of Runes: "Instant and sorcery spells you cast cost {1} less to cast."
 * Run Away Together: {1}{U} instant.
 * With Archmage on battlefield, Cast action should show manaCost = [{U}] (no generic).
 */
class CostReductionTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        val puzzleText = """
            [metadata]
            Name:Cost Reduction - Archmage of Runes
            Goal:Win
            Turns:5
            Difficulty:Tutorial
            Description:Archmage of Runes reduces instant/sorcery cost by {1}.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Run Away Together
            humanbattlefield=Archmage of Runes;Island;Island;Coral Merfolk
            humanlibrary=Island;Island;Island;Island;Island
            aibattlefield=Grizzly Bears
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
        """.trimIndent()

        fun setup(): MatchFlowHarness {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)
            return h
        }

        test("Cast action for Run Away Together shows reduced cost {U} with Archmage on battlefield") {
            val h = setup()

            // Find the ActionsAvailableReq after game start — should contain Cast for Run Away Together
            val aar = h.allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            val castActions = aar.actionsList.filter { it.actionType == ActionType.Cast }
            castActions shouldHaveSize 1

            val cast = castActions.first()
            assertSoftly {
                // Should have exactly one ManaRequirement: {U}
                cast.manaCostCount shouldBe 1
                cast.manaCostList.first().colorList shouldBe listOf(ManaColor.Blue_afc9)
                cast.manaCostList.first().count shouldBe 1
            }
        }

        test("autoTap solution taps only 1 island for reduced cost") {
            val h = setup()

            val aar = h.allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            val cast = aar.actionsList.first { it.actionType == ActionType.Cast }

            // AutoTap should tap 1 island (reduced cost {U}), not 2 (printed cost {1}{U})
            cast.hasAutoTapSolution() shouldBe true
            cast.autoTapSolution.autoTapActionsCount shouldBe 1
        }
    })
