package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class GrantedAffinityCostInteractionTest :
    SessionTest({
        for ((granted, cost) in listOf(true to 2, false to 4)) {
            session(
                "artifact creature ${if (granted) "with" else "without"} granted affinity offers and pays $cost mana",
                forgeCatalog = true,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=${if (granted) "Mycosynth Golem" else "Ornithopter"};Ornithopter;${List(
                    cost,
                ) { "Island" }.joinToString(";")}
                    humanhand=Juggernaut;Forest;Ornithopter
                    humanlibrary=Forest;Forest;Forest
                    ailibrary=Forest;Forest;Forest
                """,
            ) {
                val handId = human.hand.iid("Juggernaut")
                val offer =
                    allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                        it.actionType == ActionType.Cast && it.instanceId == handId
                    }
                val start = messageSnapshot()
                val castAccepted = castSpellByName("Juggernaut")
                passUntilResolved()
                val spellId = human.battlefield.iid("Juggernaut")
                val paid =
                    messagesSince(start).gameStateMessages().flatMap { it.annotationsList }.filter {
                        AnnotationType.ManaPaid in it.typeList && spellId in it.affectedIdsList
                    }
                val continued = playLand("Forest")
                assertSoftly {
                    offer should haveManaCost(generic = cost)
                    castAccepted shouldBe true
                    paid.size shouldBe cost
                    paid.map { it.affectorId }.distinct().size shouldBe cost
                    human.getCardsIn(ZoneType.Battlefield).count { it.name == "Island" && it.isTapped } shouldBe cost
                    game().stack.isEmpty shouldBe true
                    continued shouldBe true
                    human.battlefield.card("Forest").isTapped shouldBe false
                }
            }
        }
    })
