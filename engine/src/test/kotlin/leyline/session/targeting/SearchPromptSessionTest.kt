package leyline.session.targeting

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.assertGsIdChain

class SearchPromptSessionTest :
    SessionTest({
        test("search response keeps playback diffs before post-search state") {
            startPuzzle(
                """
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
                validating = true,
            )

            castSpellByName("Sylvan Ranger") shouldBe true
            passPriority()

            val searchReq =
                allMessages.lastOrNull { it.hasSearchReq() }?.searchReq
                    ?: error("Expected SearchReq after resolving Sylvan Ranger")
            searchReq.itemsSoughtList.shouldNotBeEmpty()

            after {
                harness.respondToSearch(listOf(searchReq.itemsSoughtList.first()))
            }

            assertGsIdChain(allMessages, context = "search response playback drain")
        }
    })
