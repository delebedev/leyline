package leyline.mechanics.imprint

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.hasCard
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ImprintLifecycleTest :
    SessionTest({
        val puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Isochron Scepter;Shock;Lightning Strike;Divination;Cancel;Llanowar Elves
            humanbattlefield=Island;Island;Island;Island
            humanlibrary=Island;Island;Island
            ailibrary=Island;Island;Island
            """

        fun MatchFlowHarness.imprintShock(): Card {
            castSpellByName("Isochron Scepter").shouldBeTrue()
            passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val eligible = lastSelectNReq().idsList.toSet()
            eligible shouldBe setOf(human.hand.iid("Shock"), human.hand.iid("Lightning Strike"))
            respondToSelectN(listOf(human.hand.iid("Shock")))
            passUntil(maxPasses = 8) { human.hasCard("Shock", ZoneType.Exile) }.shouldBeTrue()
            val shock = human.exile.card("Shock")
            val scepter = shock.exiledWith
            scepter.name shouldBe "Isochron Scepter"
            scepter.imprintedCards.toList() shouldBe listOf(shock)
            return scepter
        }

        session("imprinted instant is copied and cast for free with targets", puzzle = puzzle, fullControl = true) {
            imprintShock()
            val original = human.exile.card("Shock")
            val snapshot = messageSnapshot()
            holdNextOptionalAction()
            activateAbility("Isochron Scepter").shouldBeTrue()
            passUntil(maxPasses = 8) { messagesSince(snapshot).any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            val copy =
                bridge
                    .getGame()!!
                    .stack
                    .first()
                    .spellAbility.hostCard
            assertSoftly {
                copy.isToken shouldBe true
                copy.copiedPermanent shouldBe original
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isLand }
                    .count { it.isTapped } shouldBe 4
                human.battlefield.card("Isochron Scepter").isTapped shouldBe true
                passUntil(maxPasses = 8) { ai.life == 18 }.shouldBeTrue()
                ai.life shouldBe 18
                human.getZone(ZoneType.Exile).cards.toList() shouldBe listOf(original)
                human.battlefield
                    .card("Isochron Scepter")
                    .imprintedCards
                    .toList() shouldBe listOf(original)
            }
        }

        session("declining the optional copy cast retains the imprint and pays activation", puzzle = puzzle, fullControl = true) {
            imprintShock()
            val snapshot = messageSnapshot()
            holdNextOptionalAction()
            activateAbility("Isochron Scepter").shouldBeTrue()
            passUntil(maxPasses = 8) { messagesSince(snapshot).any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = false)
            passUntil(maxPasses = 8) { bridge.getGame()!!.stack.isEmpty }.shouldBeTrue()
            assertSoftly {
                bridge.getGame()!!.stack.isEmpty shouldBe true
                ai.life shouldBe 20
                human.getZone(ZoneType.Exile).cards.map { it.name } shouldBe listOf("Shock")
                human.battlefield.card("Isochron Scepter").isTapped shouldBe true
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isLand }
                    .count { it.isTapped } shouldBe 4
            }
        }

        session(
            "a second Scepter cannot copy the first Scepter's imprinted card",
            puzzle = puzzle.replace("humanbattlefield=", "humanbattlefield=Isochron Scepter;"),
            fullControl = true,
        ) {
            val emptyScepter = human.battlefield.card("Isochron Scepter")
            val linkedScepter = imprintShock()
            linkedScepter.id shouldNotBe emptyScepter.id
            emptyScepter.imprintedCards.shouldBeEmpty()
            val action =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Activate_add3 && it.instanceId == bridge.instanceId(emptyScepter)
                }
            val snapshot = messageSnapshot()
            submitAction(action)
            passUntil(maxPasses = 8) { bridge.getGame()!!.stack.isEmpty }.shouldBeTrue()
            assertSoftly {
                bridge.getGame()!!.stack.isEmpty shouldBe true
                messagesSince(snapshot).none { it.hasOptionalActionMessage() || it.hasSelectTargetsReq() }.shouldBeTrue()
                emptyScepter.isTapped shouldBe true
                ai.life shouldBe 20
                human.getZone(ZoneType.Exile).cards.toList() shouldBe linkedScepter.imprintedCards.toList()
            }
        }
    })
