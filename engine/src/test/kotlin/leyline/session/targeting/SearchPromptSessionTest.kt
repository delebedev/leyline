package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.assertGsIdChain
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class SearchPromptSessionTest :
    SessionTest({
        session(
            "Opposition Agent controls an opponent search and exiles the chosen card",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Scheming Symmetry
                humanbattlefield=Opposition Agent;Swamp;Forest;Forest
                humanlibrary=Forest;Island
                ailibrary=Grizzly Bears;Mountain
                """,
        ) {
            val originalController = ai.controller
            castSpellByName("Scheming Symmetry").shouldBeTrue()
            selectTargets(listOf(1, 2))
            passUntil { allMessages.any { it.hasSearchReq() } }.shouldBeTrue()
            val first = allMessages.last { it.hasSearchReq() }.searchReq
            respondToSearch(listOf(first.itemsSoughtList.first()))
            passUntil { allMessages.count { it.hasSearchReq() } == 2 }.shouldBeTrue()
            val second = allMessages.last { it.hasSearchReq() }.searchReq
            second.zonesToSearchList shouldBe listOf(ZoneIds.P2_LIBRARY)
            val chosen = ai.library.iid("Grizzly Bears")
            respondToSearch(listOf(chosen))
            passUntilResolved()
            assertSoftly {
                ai.exile.card("Grizzly Bears").name shouldBe "Grizzly Bears"
                ai.controller shouldBe originalController
                ai.controllingPlayer shouldBe null
            }
            val exiledCard = ai.exile.iid("Grizzly Bears")
            val cast =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Cast && it.instanceId == exiledCard
                }
            submitAction(cast)
            passUntilResolved()
            human.battlefield.card("Grizzly Bears").name shouldBe "Grizzly Bears"
        }
        session(
            "Opposition Agent permits playing the opponent's exiled land",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Scheming Symmetry
                humanbattlefield=Opposition Agent;Swamp
                humanlibrary=Forest;Island
                ailibrary=Mountain;Mountain
                """,
        ) {
            val originalController = ai.controller
            castSpellByName("Scheming Symmetry").shouldBeTrue()
            selectTargets(listOf(1, 2))
            passUntil { allMessages.any { it.hasSearchReq() } }.shouldBeTrue()
            respondToSearch(
                listOf(
                    allMessages
                        .last { it.hasSearchReq() }
                        .searchReq.itemsSoughtList
                        .first(),
                ),
            )
            passUntil { allMessages.count { it.hasSearchReq() } == 2 }.shouldBeTrue()
            respondToSearch(listOf(ai.library.iid("Mountain")))
            passUntilResolved()
            assertSoftly {
                ai.exile.card("Mountain").name shouldBe "Mountain"
                ai.controller shouldBe originalController
                playLand("Mountain").shouldBeTrue()
                human.battlefield.card("Mountain").name shouldBe "Mountain"
            }
        }
        session(
            "search response keeps playback diffs before post-search state",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Sylvan Ranger
                humanbattlefield=Forest;Forest
                humanlibrary=Mountain;Mountain
                aibattlefield=Forest
                ailibrary=Forest
                """,
        ) {
            castSpellUntilSearchReq("Sylvan Ranger")
            val searchMessage = allMessages.last { it.hasSearchReq() }
            val searchReq = searchMessage.searchReq
            searchReq.itemsSoughtList.shouldNotBeEmpty()
            check(searchReq.sourceId != 0) { "Triggered search must retain its engine-side source identity" }
            val hostId =
                searchMessage.prompt.parametersList
                    .first()
                    .numberValue
            check(hostId != 0) {
                "Triggered search must retain its host-card identity"
            }
            check(searchReq.sourceId != hostId) { "Triggered search ability and host identities must remain distinct" }
            val requestIndex = allMessages.indexOfLast { it.hasSearchReq() }
            val libraryIids = searchReq.itemsSoughtList.toSet()
            check(
                allMessages
                    .take(requestIndex)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .any { it.instanceId in libraryIids },
            ) { "Library objects must be published before SearchReq" }

            respondToSearch(listOf(searchReq.itemsSoughtList.first()))
            human.hand.card("Mountain").name shouldBe "Mountain"

            assertGsIdChain(allMessages, context = "search response playback drain")
        }
    })
