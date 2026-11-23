package leyline.mechanics.suspend

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.BoardTest
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Suspend's special action from hand rides the hand alternative-cost Cast
 * rail: `alternativeGrpId` names the printing's suspend row, and the mana
 * cost is the suspend cost keyed by that row.
 */
@Suppress("WeakAssertionOnly")
class SuspendActionTest :
    BoardTest({

        test("each suspend card in hand offers its suspend action beside any normal cast") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Mountain", human, ZoneType.Battlefield) }
                    addCard("Mox Tantalite", human, ZoneType.Hand)
                    addCard("Wheel of Fate", human, ZoneType.Hand)
                    addCard("Rift Bolt", human, ZoneType.Hand)
                }
            val repo = board.bridge.cardRepository
            val actions = board.actions()

            fun suspendRow(name: String): Int = repo.findKeywordAbilityGrpId(repo.findGrpIdByName(name)!!, KeywordAbilityIds.SUSPEND)!!

            fun casts(name: String) =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast &&
                        it.grpId == repo.findGrpIdByName(name)
                }

            val moxSuspend = suspendRow("Mox Tantalite")
            val wheelSuspend = suspendRow("Wheel of Fate")
            val boltSuspend = suspendRow("Rift Bolt")

            assertSoftly {
                casts("Mox Tantalite").map { it.alternativeGrpId } shouldContainExactly listOf(moxSuspend)
                casts("Mox Tantalite").single().manaCostList shouldHaveSize 0

                casts("Wheel of Fate").map { it.alternativeGrpId } shouldContainExactly listOf(wheelSuspend)
                casts(
                    "Wheel of Fate",
                ).single().manaCostList.map { Triple(it.colorList, it.count, it.abilityGrpId) } shouldContainExactlyInAnyOrder
                    listOf(
                        Triple(listOf(ManaColor.Red_afc9), 1, wheelSuspend),
                        Triple(listOf(ManaColor.Generic), 1, wheelSuspend),
                    )

                casts("Rift Bolt").map { it.alternativeGrpId } shouldContainExactly listOf(0, boltSuspend)
                casts("Rift Bolt").single { it.alternativeGrpId == boltSuspend }.manaCostList.map {
                    Triple(it.colorList, it.count, it.abilityGrpId)
                } shouldContainExactly listOf(Triple(listOf(ManaColor.Red_afc9), 1, boltSuspend))
                casts("Rift Bolt").single { it.alternativeGrpId == 0 }.manaCostList.sumOf { it.count } shouldBe 3
            }
        }

        test("suspend is offered only from hand and only when its cost is payable") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Wheel of Fate", human, ZoneType.Hand)
                    addCard("Rift Bolt", human, ZoneType.Graveyard)
                }
            val repo = board.bridge.cardRepository
            val actions: ActionsAvailableReq = board.actions()

            fun suspendRow(name: String): Int = repo.findKeywordAbilityGrpId(repo.findGrpIdByName(name)!!, KeywordAbilityIds.SUSPEND)!!

            assertSoftly {
                actions shouldNot offerAltCost(suspendRow("Wheel of Fate"))
                actions shouldNot offerAltCost(suspendRow("Rift Bolt"))
                actions.actionsList.none { it.actionType == ActionType.Cast } shouldBe true
            }
        }
    })
