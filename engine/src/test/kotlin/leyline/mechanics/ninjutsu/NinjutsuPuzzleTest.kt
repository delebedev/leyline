package leyline.mechanics.ninjutsu

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.allGameObjects
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AttackState

private const val NINJUTSU_GRP_ID = 5341

class NinjutsuPuzzleTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Island")
            TestCardRegistry.ensureCardRegistered("Ninja of the Deep Hours")
        }

        fun hasNinjutsuOffer(): Boolean =
            allMessages
                .asReversed()
                .firstOrNull { it.hasActionsAvailableReq() }
                ?.actionsAvailableReq
                ?.actionsList
                ?.filter { it.actionType == ActionType.Activate_add3 }
                ?.any { it.abilityGrpId == NINJUTSU_GRP_ID } == true

        fun zoneSummary(): String =
            "hand=${human.getZone(ZoneType.Hand).cards.map { it.name }} " +
                "battlefield=${human.getZone(ZoneType.Battlefield).cards.map { it.name }} " +
                "graveyard=${human.getZone(ZoneType.Graveyard).cards.map { it.name }} " +
                "gameOver=${isGameOver()} phase=${phase()}"

        test("Ninjutsu returns an unblocked attacker and enters tapped attacking") {
            startPuzzleRaw(
                """
                [metadata]
                Name:Ninjutsu Deep Hours
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:Activate Ninjutsu after an attacker goes unblocked.

                [state]
                ActivePlayer=Human
                ActivePhase=COMBAT_DECLARE_ATTACKERS
                HumanLife=20
                AILife=20

                humanhand=Ninja of the Deep Hours
                humanbattlefield=Island;Island;Raging Goblin
                humanlibrary=Island;Island;Island
                aibattlefield=
                ailibrary=Island;Island;Island
                """.trimIndent(),
                validating = true,
            )

            passUntil(maxPasses = 5) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()

            val attackerIid = human.battlefield.iid("Raging Goblin")
            declareAttackers(listOf(attackerIid))

            passUntil(maxPasses = 5) { hasNinjutsuOffer() }.shouldBeTrue()

            val offer =
                allMessages
                    .asReversed()
                    .first { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .first {
                        it.actionType == ActionType.Activate_add3 &&
                            it.abilityGrpId == NINJUTSU_GRP_ID
                    }
            offer.manaCostList.map { it.abilityGrpId }.shouldContain(NINJUTSU_GRP_ID)

            val activated = activateAbilityFromHand("Ninja of the Deep Hours")
            activated.shouldBeTrue()
            val payCostsOffered = passUntil(maxPasses = 5) { allMessages.any { it.hasPayCostsReq() } }
            payCostsOffered.shouldBeTrue()

            val payCostsMessage = allMessages.last { it.hasPayCostsReq() }
            val returnCostReq = payCostsMessage.payCostsReq.effectCostReq.costSelection
            assertSoftly {
                payCostsMessage.prompt.promptId shouldBe PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST
                payCostsMessage.prompt.parametersList
                    .first { it.parameterName == "CardId" }
                    .numberValue shouldNotBe 0
                returnCostReq.idsList.shouldContain(attackerIid)
            }
            val postCostSnap = harness.messageSnapshot()
            respondToEffectCost(listOf(attackerIid))
            val postCostMessages = harness.messagesSince(postCostSnap)

            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Ninja of the Deep Hours" }
            }.shouldBeTrue()

            val ninja =
                withClue(zoneSummary()) {
                    human.getZone(ZoneType.Battlefield).cards.single { it.name == "Ninja of the Deep Hours" }
                }
            assertSoftly {
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .map { it.name }
                    .shouldContain("Raging Goblin")
                ai.life shouldBe 18
                ninja.isTapped.shouldBeTrue()
                val ninjaIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(ninja.id)).value
                postCostMessages
                    .allGameObjects()
                    .firstOrNull { it.instanceId == ninjaIid && it.attackState == AttackState.Attacking }
                    .shouldNotBeNull()
                    .isTapped
                    .shouldBeTrue()
            }
        }
    })
