package leyline.board.costs

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.BundleBuilderTestSupport
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Static cost reduction — Archmage of Runes + Run Away Together.
 *
 * Archmage of Runes: "Instant and sorcery spells you cast cost {1} less to cast."
 * Run Away Together: {1}{U} instant.
 * With Archmage on battlefield, Cast action should show manaCost = [{U}] (no generic).
 */
class CostReductionTest :
    BoardTest({

        val puzzleText =
            """
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

        test("Cast action for Run Away Together shows reduced cost {U} with Archmage on battlefield") {
            val (b, _, _) = startPuzzleAtMain1(puzzleText)
            val actions = BundleBuilderTestSupport.buildActions(b)

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            castActions shouldHaveSize 1

            val cast = castActions.first()
            cast should haveManaCost(blue = 1)
        }

        test("autoTap solution taps only 1 island for reduced cost") {
            val (b, _, _) = startPuzzleAtMain1(puzzleText)
            val actions = BundleBuilderTestSupport.buildActions(b)

            val cast = actions.actionsList.first { it.actionType == ActionType.Cast }

            cast.hasAutoTapSolution() shouldBe true
            cast.autoTapSolution.autoTapActionsCount shouldBe 1
        }
    })
