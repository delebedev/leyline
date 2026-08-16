package leyline.match

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

class OptionalActionHandlerTest :
    SessionTest({
        test("accepted optional action publishes a chained search without waiting for action priority") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Formidable Speaker;Forest
                humanbattlefield=Forest;Forest;Forest
                humanlibrary=Grizzly Bears
                ailibrary=Mountain
                """,
                name = "Optional action chained selection",
                turns = 3,
                validating = true,
            )
            harness.holdNextOptionalAction()

            castSpellByName("Formidable Speaker") shouldBe true
            passUntil(maxPasses = 4) { allMessages.any { it.hasOptionalActionMessage() } } shouldBe true

            harness.respondToOptionalAction(accept = true)
            val search = allMessages.lastOrNull { it.hasSearchReq() }?.searchReq ?: error("Expected chained SearchReq")

            search.itemsSoughtCount shouldBe 1
            harness.respondToSearch(search.itemsSoughtList)
            human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Grizzly Bears"
        }
    })
