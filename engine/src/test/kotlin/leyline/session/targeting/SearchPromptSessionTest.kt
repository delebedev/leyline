package leyline.session.targeting

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.assertGsIdChain

class SearchPromptSessionTest :
    SessionTest({
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
            castSpellByName("Sylvan Ranger") shouldBe true
            passPriority()

            val searchMessage = allMessages.lastOrNull { it.hasSearchReq() } ?: error("Expected SearchReq after resolving Sylvan Ranger")
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

            after {
                respondToSearch(listOf(searchReq.itemsSoughtList.first()))
            }

            assertGsIdChain(allMessages, context = "search response playback drain")
        }
    })
