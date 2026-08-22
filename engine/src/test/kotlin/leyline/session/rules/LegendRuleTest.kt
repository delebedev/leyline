package leyline.session.rules

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.assertGsIdChain
import leyline.testkit.detailString
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Legend rule SBA conformance: when two legendary permanents with the same
 * name are on the battlefield, the engine presents a SelectNReq (choose 1 to keep),
 * then sacrifices the other with SBA_LegendRule transfer category.
 *
 * Flow:
 * 1. SelectNReq: context=Resolution, listType=Dynamic, min=1, max=1, ids=[old, new]
 * 2. Client responds SelectNResp with chosen instanceId
 * 3. Resolution Diff: ZoneTransfer(SBA_LegendRule) + ObjectIdChanged
 */
class LegendRuleTest :
    SessionTest({

        val puzzleText =
            """
            [metadata]
            Name:Legend Rule Test
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Cast second Isamaru to trigger legend rule.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=2

            humanhand=Isamaru, Hound of Konda
            humanbattlefield=Isamaru, Hound of Konda|Tapped;Fervor;Plains;Plains
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        fun HeadlessMatch.findUntappedIsamaru(instanceIds: List<Int>): Int? {
            for (iid in instanceIds) {
                val card = cardByIid(iid)
                if (card != null && !card.isTapped) return iid
            }
            return null
        }

        /** Cast Isamaru, resolve, trigger legend rule, respond to SelectNReq. */
        fun HeadlessMatch.castAndResolveLegendRule(): Int {
            castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            passPriority()

            val selectNReq = allMessages.last { it.hasSelectNReq() }
            val legendaryIds = selectNReq.selectNReq.idsList
            val keepId = findUntappedIsamaru(legendaryIds) ?: legendaryIds.last()

            respondToSelectN(listOf(keepId))
            return keepId
        }

        session("SelectNReq carries the documented context/listType/idType fields", puzzle = puzzleText) {
            val req = castSpellUntilSelectNReq("Isamaru, Hound of Konda")

            assertSoftly {
                req.idsList.size shouldBe 2
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.Dynamic
                req.idType shouldBe IdType.InstanceId_ab2c
                req.validationType shouldBe SelectionValidationType.NonRepeatable
            }
        }

        session("SBA_LegendRule transfer category", puzzle = puzzleText) {
            val resolution = after { castAndResolveLegendRule() }

            val allAnnotations =
                resolution.messages.flatMap { msg ->
                    if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList()
                }
            allAnnotations
                .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                .count { it.detailString("category") == "SBA_LegendRule" } shouldBe 1
        }

        session("keeps chosen legendary on battlefield", puzzle = puzzleText) {
            castAndResolveLegendRule()

            val bfIsamarus =
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Isamaru, Hound of Konda" }
            bfIsamarus.size shouldBe 1

            val gyCards = human.getZone(ForgeZoneType.Graveyard).cards
            gyCards.any { it.name == "Isamaru, Hound of Konda" } shouldBe true
        }

        session("ObjectIdChanged annotation present", puzzle = puzzleText) {
            val resolution = after { castAndResolveLegendRule() }

            val allAnnotations =
                resolution.messages.flatMap { msg ->
                    if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList()
                }
            allAnnotations.count { AnnotationType.ObjectIdChanged in it.typeList } shouldBeGreaterThan 0
        }

        session("state validity after legend rule", puzzle = puzzleText) {
            castAndResolveLegendRule()

            assertSoftly {
                assertConsistent("after legend rule")
                assertGsIdChain(allMessages, context = "legend rule flow")
                isGameOver().shouldBeFalse()
            }
        }
    })
