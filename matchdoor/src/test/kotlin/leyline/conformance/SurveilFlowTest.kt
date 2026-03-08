package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Surveil flow tests using [MatchFlowHarness] + surveil-etb.pzl puzzle.
 *
 * Wary Thespian has "When this creature enters, surveil 1."
 * Puzzle puts Grizzly Bears on top of library, making the surveil choice meaningful.
 *
 * Tests verify that casting Wary Thespian produces a GroupReq (not auto-resolved),
 * and that GroupResp correctly routes the card to library top or graveyard.
 */
class SurveilFlowTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        fun setupSurveil(): MatchFlowHarness {
            val h = MatchFlowHarness()
            harness = h
            h.connectAndKeepPuzzle("puzzles/surveil-etb.pzl")
            return h
        }

        test("surveil ETB emits GroupReq") {
            val h = setupSurveil()

            val snap = h.messageSnapshot()
            val cast = h.castSpellByName("Wary Thespian")
            cast.shouldBeTrue()

            // Pass priority to resolve the creature (move from stack to battlefield)
            h.passPriority()

            val msgs = h.messagesSince(snap)
            val groupReq = msgs.firstOrNull { it.hasGroupReq() }
            groupReq.shouldNotBeNull()

            val req = groupReq.groupReq
            req.context shouldBe GroupingContext.Surveil
            req.instanceIdsList.shouldNotBeEmpty()
            req.groupSpecsList.size shouldBe 2

            // First spec: Library/Top (keep)
            req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
            req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top

            // Second spec: Graveyard (away)
            req.groupSpecsList[1].zoneType shouldBe ZoneType.Graveyard
        }

        test("surveil keep on top") {
            val h = setupSurveil()

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            // Find the GroupReq to get instanceIds
            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            // Respond: keep on top (empty away group)
            h.respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = cardIds)

            // Grizzly Bears should still be on top of library
            val player = h.bridge.getPlayer(1)!!
            val libCards = player.getZone(ForgeZoneType.Library).cards
            val topCard = libCards.lastOrNull()
            topCard.shouldNotBeNull()
            topCard.name shouldBe "Grizzly Bears"

            // Grizzly Bears should NOT be in graveyard
            val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
            val bearsInGy = gyCards.any { it.name.equals("Grizzly Bears", ignoreCase = true) }
            bearsInGy.shouldBeFalse()

            h.accumulator.assertConsistent("after surveil keep on top")
        }

        test("surveil put in graveyard") {
            val h = setupSurveil()

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            // Respond: put in graveyard (all cards go to away group)
            h.respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            // Grizzly Bears should be in graveyard
            val player = h.bridge.getPlayer(1)!!
            val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
            val bearsInGy = gyCards.any { it.name.equals("Grizzly Bears", ignoreCase = true) }
            bearsInGy.shouldBeTrue()

            // Grizzly Bears should NOT be on top of library
            val libCards = player.getZone(ForgeZoneType.Library).cards
            val topCard = libCards.lastOrNull()
            if (topCard != null) {
                (topCard.name != "Grizzly Bears").shouldBeTrue()
            }

            h.accumulator.assertConsistent("after surveil to graveyard")
        }

        test("surveil state validity") {
            val h = setupSurveil()

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            h.respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            h.accumulator.assertConsistent("after surveil flow")
            assertGsIdChain(h.allMessages, context = "surveil flow")
            h.isGameOver().shouldBeFalse()
        }
    })
