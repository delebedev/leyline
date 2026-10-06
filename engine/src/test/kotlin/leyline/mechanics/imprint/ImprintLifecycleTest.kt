package leyline.mechanics.imprint

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.hasCard
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

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

        fun MatchFlowHarness.imprintShock(extraEligible: List<String> = emptyList()): Card {
            castSpellByName("Isochron Scepter").shouldBeTrue()
            passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val eligible = lastSelectNReq().idsList.toSet()
            eligible shouldBe (listOf("Shock", "Lightning Strike") + extraEligible).map { human.hand.iid(it) }.toSet()
            respondToSelectN(listOf(human.hand.iid("Shock")))
            passUntil(maxPasses = 8) { human.hasCard("Shock", ZoneType.Exile) }.shouldBeTrue()
            val shock = human.exile.card("Shock")
            val scepter = shock.exiledWith
            scepter.name shouldBe "Isochron Scepter"
            scepter.imprintedCards.toList() shouldBe listOf(shock)
            val display = allMessages.persistentAnnotationsOfType(AnnotationType.DisplayCardUnderCard).single()
            display.affectorId shouldBe bridge.instanceId(scepter)
            display.affectedIdsList shouldBe listOf(bridge.instanceId(shock))
            display.detailsList.map { it.key } shouldBe listOf("Disable")
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

        session(
            "targeted battlefield imprint publishes one non-temporary display",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Duplicant
                humanbattlefield=Island;Island;Island;Island;Island;Island
                humanlibrary=Island;Island;Island
                aibattlefield=Centaur Courser
                ailibrary=Island;Island;Island
                """,
            fullControl = true,
        ) {
            castSpellByName("Duplicant").shouldBeTrue()
            passUntil(8) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntil(8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = true)
            passUntil(8) { ai.hasCard("Centaur Courser", ZoneType.Exile) }.shouldBeTrue()
            val display = allMessages.persistentAnnotationsOfType(AnnotationType.DisplayCardUnderCard).single()
            display.affectorId shouldBe human.battlefield.iid("Duplicant")
            display.affectedIdsList shouldBe listOf(ai.exile.iid("Centaur Courser"))
            display.detailsList.map { it.key } shouldBe listOf("Disable")
            passPriority()
            allMessages.persistentAnnotationsOfType(AnnotationType.DisplayCardUnderCard).single().id shouldBe display.id
        }

        for (removeSource in listOf(true, false)) {
            session(
                if (removeSource) {
                    "destroying the source retires its imprint display without returning the card"
                } else {
                    "moving the imprinted card out of exile retires its display while the source remains"
                },
                puzzle =
                    puzzle
                        .replace("humanhand=", "humanhand=Disenchant;Pull from Eternity;")
                        .replace("humanbattlefield=", "humanbattlefield=Plains;Plains;Plains;"),
                fullControl = true,
            ) {
                val scepter = imprintShock(listOf("Disenchant", "Pull from Eternity"))
                val shock = human.exile.card("Shock")
                val display = allMessages.persistentAnnotationsOfType(AnnotationType.DisplayCardUnderCard).single()
                val snapshot = messageSnapshot()
                castSpellUntilSelectTargetsReq(if (removeSource) "Disenchant" else "Pull from Eternity")
                selectTargets(listOf(bridge.instanceId(if (removeSource) scepter else shock)))
                passUntil(8) {
                    human.hasCard(if (removeSource) "Isochron Scepter" else "Shock", ZoneType.Graveyard)
                }.shouldBeTrue()
                messagesSince(snapshot)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.diffDeletedPersistentAnnotationIdsList } shouldContain display.id
                if (removeSource) {
                    human.exile.card("Shock") shouldBe shock
                } else {
                    human.battlefield.card("Isochron Scepter") shouldBe scepter
                }
            }
        }
    })
