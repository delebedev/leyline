package leyline.conformance

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
 * Real server flow (recording: 2026-03-17_20-18-39 gsId=681-682):
 * 1. SelectNReq: context=Resolution, listType=Dynamic, min=1, max=1, ids=[old, new]
 * 2. Client responds SelectNResp with chosen instanceId
 * 3. Resolution Diff: ZoneTransfer(SBA_LegendRule) + ObjectIdChanged
 *
 * Uses [MatchFlowHarness] + inline puzzle — full pipeline, engine on daemon thread.
 */
class LegendRuleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        /**
         * Puzzle: two Isamarus on BF (one tapped), Fervor for haste, one Isamaru in hand.
         * Cast the hand Isamaru → legend rule fires → SelectNReq.
         */
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

        fun setup(validating: Boolean = true): MatchFlowHarness {
            val h = MatchFlowHarness(validating = validating)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)
            return h
        }

        test("legend rule SBA emits SelectNReq") {
            val h = setup(validating = false)
            val snap = h.messageSnapshot()

            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            // Pass priority to resolve the creature (stack → battlefield)
            h.passPriority()

            val msgs = h.messagesSince(snap)
            val selectNReq = msgs.firstOrNull { it.hasSelectNReq() }
            selectNReq.shouldNotBeNull()

            val req = selectNReq.selectNReq
            req.idsList.shouldNotBeEmpty()
            req.idsList.size shouldBe 2 // two Isamarus
            req.minSel shouldBe 1
            req.maxSel shouldBe 1
        }

        test("legend rule SBA produces SBA_LegendRule transfer category") {
            val h = setup(validating = false)
            val snap = h.messageSnapshot()

            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            h.passPriority()

            // Find the SelectNReq to get the instanceIds
            val selectNReq = h.allMessages.last { it.hasSelectNReq() }
            val legendaryIds = selectNReq.selectNReq.idsList

            // Find the new Isamaru (untapped, just entered) — keep it
            val newIsamaruId = findUntappedIsamaru(h, legendaryIds)
                ?: legendaryIds.last() // fallback

            h.respondToSelectN(listOf(newIsamaruId))

            // Verify ZoneTransfer with SBA_LegendRule in post-SelectN messages
            val msgs = h.messagesSince(snap)
            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }
            val zt = allAnnotations.filter { ann ->
                ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a }
            }
            zt.shouldNotBeEmpty()

            val legendRuleZt = zt.firstOrNull { ann ->
                val cat = ann.detailsList.firstOrNull { it.key == "category" }
                    ?.valueStringList?.firstOrNull()
                cat == "SBA_LegendRule"
            }
            legendRuleZt.shouldNotBeNull()
        }

        test("legend rule keeps chosen legendary on battlefield") {
            val h = setup(validating = false)

            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            h.passPriority()

            val selectNReq = h.allMessages.last { it.hasSelectNReq() }
            val legendaryIds = selectNReq.selectNReq.idsList

            // Keep the new (untapped) Isamaru
            val newIsamaruId = findUntappedIsamaru(h, legendaryIds)
                ?: legendaryIds.last()
            val sacrificedId = legendaryIds.first { it != newIsamaruId }

            h.respondToSelectN(listOf(newIsamaruId))

            // The kept one should be on battlefield
            val player = h.bridge.getPlayer(SeatId(1))!!
            val bfIsamarus = player.getZone(ForgeZoneType.Battlefield).cards
                .filter { it.name == "Isamaru, Hound of Konda" }
            bfIsamarus.size shouldBe 1

            // The sacrificed one should be in graveyard
            val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
            gyCards.any { it.name == "Isamaru, Hound of Konda" } shouldBe true
        }

        test("legend rule produces ObjectIdChanged annotation") {
            val h = setup(validating = false)
            val snap = h.messageSnapshot()

            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            h.passPriority()

            val selectNReq = h.allMessages.last { it.hasSelectNReq() }
            val legendaryIds = selectNReq.selectNReq.idsList
            val newIsamaruId = findUntappedIsamaru(h, legendaryIds)
                ?: legendaryIds.last()

            h.respondToSelectN(listOf(newIsamaruId))

            val msgs = h.messagesSince(snap)
            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }

            // ObjectIdChanged should be present for the sacrificed legendary
            val oidChanged = allAnnotations.filter { ann ->
                ann.typeList.any { it == AnnotationType.ObjectIdChanged }
            }
            oidChanged.shouldNotBeEmpty()
        }

        test("legend rule state validity") {
            val h = setup(validating = false)

            h.castSpellByName("Isamaru, Hound of Konda").shouldBeTrue()
            h.passPriority()

            val selectNReq = h.allMessages.last { it.hasSelectNReq() }
            val legendaryIds = selectNReq.selectNReq.idsList
            val newIsamaruId = findUntappedIsamaru(h, legendaryIds)
                ?: legendaryIds.last()

            h.respondToSelectN(listOf(newIsamaruId))

            h.accumulator.assertConsistent("after legend rule")
            assertGsIdChain(h.allMessages, context = "legend rule flow")
            h.isGameOver().shouldBeFalse()
        }
    })

/**
 * Find the untapped Isamaru among the SelectNReq candidates.
 * The tapped one is the original battlefield creature; the untapped one
 * just entered from the stack.
 */
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
