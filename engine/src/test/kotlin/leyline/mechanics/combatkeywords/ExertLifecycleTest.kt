package leyline.mechanics.combatkeywords

import forge.game.combat.CombatUtil
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages

class ExertLifecycleTest :
    SessionTest({
        for (exert in listOf(false, true)) {
            session(
                "${if (exert) "Exert" else "Normal"} attack preserves its choice through declaration echoes",
                puzzle = exertPuzzle,
                fullControl = true,
            ) {
                val stalwart = human.battlefield.card("Rhonas's Stalwart")
                val stalwartIid = human.battlefield.iid(stalwart.name)
                val courserIid = human.battlefield.iid("Centaur Courser")
                val blocker = ai.battlefield.card("Grizzly Bears")
                advanceToAttackers()

                // Switching from Exert to normal must replace the retained alternative.
                toggleAttackers(listOf(stalwartIid), mapOf(stalwartIid to KeywordAbilityIds.EXERT))
                if (!exert) toggleAttackers(listOf(stalwartIid))
                val echo = toggleAttackers(listOf(courserIid)).last { it.hasDeclareAttackersReq() }.declareAttackersReq
                val options = echo.attackersList.filter { it.attackerInstanceId == stalwartIid }
                val selectedAlternative = if (exert) KeywordAbilityIds.EXERT else 0
                assertSoftly {
                    options.map { it.alternativeGrpId }.toSet() shouldBe setOf(0, KeywordAbilityIds.EXERT)
                    options.single { it.hasSelectedDamageRecipient() }.alternativeGrpId shouldBe selectedAlternative
                    echo.qualifiedAttackersList.any { it.hasSelectedDamageRecipient() }.shouldBeFalse()
                    echo.attackersList.filter { it.attackerInstanceId == courserIid }.map { it.alternativeGrpId } shouldBe listOf(0)
                }

                val snap = messageSnapshot()
                submitAttackers()
                val expectedPower = if (exert) 3 else 2
                passUntil(maxPasses = 20) {
                    game().phaseHandler.combat?.isAttacking(stalwart) == true &&
                        game().stack.isEmpty &&
                        stalwart.netPower == expectedPower
                }.shouldBeTrue()
                assertSoftly {
                    stalwart.exertedThisTurn shouldBe if (exert) 1 else 0
                    stalwart.netPower shouldBe expectedPower
                    stalwart.netToughness shouldBe expectedPower
                    CombatUtil.canBlock(stalwart, blocker) shouldBe !exert
                    messagesSince(snap).any { it.hasOrderReq() }.shouldBeFalse()
                    messagesSince(snap)
                        .gameStateMessages()
                        .flatMap { it.gameObjectsList }
                        .any {
                            it.instanceId == stalwartIid && it.power.value == expectedPower && it.toughness.value == expectedPower
                        }.shouldBeTrue()
                }
            }
        }

        session("Exert consumption preserves a different attacker's Enlist choice", puzzle = mixedPuzzle, fullControl = true) {
            val stalwartIid = human.battlefield.iid("Rhonas's Stalwart")
            val faithbonderIid = human.battlefield.iid("Benalish Faithbonder")
            val courserIid = human.battlefield.iid("Centaur Courser")
            advanceToAttackers()
            toggleAttackers(listOf(stalwartIid), mapOf(stalwartIid to KeywordAbilityIds.EXERT))
            toggleAttackers(listOf(faithbonderIid), mapOf(faithbonderIid to KeywordAbilityIds.ENLIST))
            val snap = messageSnapshot()
            submitAttackers()
            passUntil(maxPasses = 10) { messagesSince(snap).any { it.hasPayCostsReq() } }.shouldBeTrue()
            messagesSince(snap)
                .last { it.hasPayCostsReq() }
                .payCostsReq.effectCostReq.costSelection.idsList shouldContain courserIid
            respondToEffectCost(listOf(courserIid))
            passUntil(maxPasses = 20) {
                human.battlefield.card("Rhonas's Stalwart").netPower == 3 &&
                    human.battlefield.card("Benalish Faithbonder").netPower == 4 &&
                    game().stack.isEmpty
            }.shouldBeTrue()
            assertSoftly {
                human.battlefield.card("Rhonas's Stalwart").exertedThisTurn shouldBe 1
                human.battlefield.card("Benalish Faithbonder").exertedThisTurn shouldBe 0
                human.battlefield
                    .card("Centaur Courser")
                    .isTapped
                    .shouldBeTrue()
            }
        }
    }) {
    companion object {
        private fun MatchFlowHarness.advanceToAttackers() {
            passUntil(maxPasses = 10) {
                bridge
                    .actionBridge(SeatId(HUMAN_SEAT))
                    .getPending()
                    ?.state
                    ?.kind == PendingActionKind.DECLARE_ATTACKERS
            }.shouldBeTrue()
        }

        private val exertPuzzle =
            """
            [metadata]
            Name:Exert attack choices
            Goal:Win
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            removesummoningsickness=true
            humanbattlefield=Rhonas's Stalwart;Centaur Courser
            aibattlefield=Grizzly Bears
            humanlibrary=Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        private val mixedPuzzle =
            exertPuzzle.replace(
                "Rhonas's Stalwart;Centaur Courser",
                "Rhonas's Stalwart;Benalish Faithbonder;Centaur Courser",
            )
    }
}
