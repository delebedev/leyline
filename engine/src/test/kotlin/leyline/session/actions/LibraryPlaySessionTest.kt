package leyline.session.actions

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class LibraryPlaySessionTest :
    SessionTest({
        session(
            "Glarb casts the eligible top spell and plays the next top land",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Glarb, Calamity's Augur;Forest;Forest;Island;Swamp
                humanlibrary=Rumbling Baloth;Mountain;Island
                ailibrary=Mountain
                """,
        ) {
            val top = human.library.iid("Rumbling Baloth")
            val offered =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Cast && it.instanceId == top
                }
            submitAction(offered)
            passUntilResolved()
            playLand("Mountain").shouldBeTrue()
            assertSoftly {
                human.battlefield.card("Rumbling Baloth").name shouldBe "Rumbling Baloth"
                human.battlefield.card("Mountain").name shouldBe "Mountain"
                human.landsPlayedThisTurn shouldBe 1
            }
        }
        session(
            "Glarb leaving withdraws the top-library cast action",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Unsummon
                humanbattlefield=Glarb, Calamity's Augur;Forest;Forest;Island;Swamp;Island
                humanlibrary=Rumbling Baloth;Mountain;Island
                ailibrary=Mountain
                """,
        ) {
            val top = human.library.iid("Rumbling Baloth")
            castSpellByName("Unsummon").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Glarb, Calamity's Augur")))
            passUntilResolved()
            human.hand.card("Glarb, Calamity's Augur").name shouldBe "Glarb, Calamity's Augur"
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .filter { it.actionType == ActionType.Cast && it.instanceId == top }
                .shouldBeEmpty()
        }
    })
