package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
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
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        val puzzleText = """
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

        fun setup(): MatchFlowHarness {
            val h = MatchFlowHarness()
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)
            return h
        }

        /** Cast Isamaru, resolve, trigger legend rule, respond to SelectNReq. */
        fun castAndResolveLegendRule(h: MatchFlowHarness): Int {
            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            h.passPriority()

            val selectNReq = h.allMessages.last { it.hasSelectNReq() }
            val legendaryIds = selectNReq.selectNReq.idsList
            val keepId = findUntappedIsamaru(h, legendaryIds) ?: legendaryIds.last()

            h.respondToSelectN(listOf(keepId))
            return keepId
        }

        test("SelectNReq shape matches wire spec") {
            val h = setup()

            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            h.passPriority()

            val msg = h.allMessages.last { it.hasSelectNReq() }
            val req = msg.selectNReq

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

        test("SBA_LegendRule transfer category") {
            val h = setup()
            val snap = h.messageSnapshot()

            castAndResolveLegendRule(h)

            val allAnnotations = h.messagesSince(snap).flatMap { msg ->
                if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList()
            }
            val zt = allAnnotations
                .filter { it.typeList.any { t -> t == AnnotationType.ZoneTransfer_af5a } }
                .firstOrNull { it.detailString("category") == "SBA_LegendRule" }
            zt.shouldNotBeNull()
        }

        test("keeps chosen legendary on battlefield") {
            val h = setup()

            castAndResolveLegendRule(h)

            val player = h.bridge.getPlayer(SeatId(1))!!
            val bfIsamarus = player.getZone(ForgeZoneType.Battlefield).cards
                .filter { it.name == "Isamaru, Hound of Konda" }
            bfIsamarus.size shouldBe 1

            val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
            gyCards.any { it.name == "Isamaru, Hound of Konda" } shouldBe true
        }

        test("ObjectIdChanged annotation present") {
            val h = setup()
            val snap = h.messageSnapshot()

            castAndResolveLegendRule(h)

            val allAnnotations = h.messagesSince(snap).flatMap { msg ->
                if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList()
            }
            allAnnotations.filter {
                it.typeList.any { t -> t == AnnotationType.ObjectIdChanged }
            }.shouldNotBeEmpty()
        }

        test("state validity after legend rule") {
            val h = setup()

            castAndResolveLegendRule(h)

            h.accumulator.assertConsistent("after legend rule")
            assertGsIdChain(h.allMessages, context = "legend rule flow")
            h.isGameOver().shouldBeFalse()
        }
    })

private fun findUntappedIsamaru(h: MatchFlowHarness, instanceIds: List<Int>): Int? {
    val player = h.bridge.getPlayer(SeatId(1)) ?: return null
    for (iid in instanceIds) {
        val forgeCardId = h.bridge.getForgeCardId(InstanceId(iid)) ?: continue
        val card = player.getZone(ForgeZoneType.Battlefield).cards
            .firstOrNull { it.id == forgeCardId.value }
        if (card != null && !card.isTapped) return iid
    }
    return null
}
