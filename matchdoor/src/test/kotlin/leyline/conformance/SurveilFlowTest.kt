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

        fun setupSurveil(validating: Boolean = true): MatchFlowHarness {
            val h = MatchFlowHarness(validating = validating)
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

            // The revealed card must be the actual surveil target (Grizzly Bears),
            // not the next card in library. This catches the bug where the engine
            // already removed the card before we read library top.
            val revealedInstanceId = req.instanceIdsList.first()
            val forgeCardId = h.bridge.getForgeCardId(InstanceId(revealedInstanceId))
            forgeCardId.shouldNotBeNull()
            val card = h.bridge.getGame()!!.findById(checkNotNull(forgeCardId).value)
            card.shouldNotBeNull()
            card.name shouldBe "Grizzly Bears"
        }

        // TODO: re-enable strict validation after fixing surveil zone transfer annotations (#66)
        test("surveil keep on top") {
            val h = setupSurveil(validating = false)

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            // Find the GroupReq to get instanceIds
            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            // Respond: keep on top (empty away group)
            h.respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = cardIds)

            // Grizzly Bears should NOT be in graveyard (kept on top of library)
            val player = h.bridge.getPlayer(SeatId(1))!!
            val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
            val bearsInGy = gyCards.any { it.name.equals("Grizzly Bears", ignoreCase = true) }
            bearsInGy.shouldBeFalse()
        }

        test("surveil put in graveyard") {
            val h = setupSurveil(validating = false)

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            // Respond: put in graveyard (all cards go to away group)
            h.respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            // Grizzly Bears should be in graveyard
            val player = h.bridge.getPlayer(SeatId(1))!!
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

        test("surveil to graveyard produces ZoneTransfer with Surveil category") {
            val h = setupSurveil(validating = false)
            val snap = h.messageSnapshot()

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            // Put card in graveyard
            h.respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            // Find ZoneTransfer annotation in post-GroupResp messages
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

            // Find the surveil-specific ZoneTransfer (not CastSpell/Resolve)
            // by checking category is either "Surveil" or "Mill" (Library→GY transfer)
            val surveilZt = zt.firstOrNull { ann ->
                val cat = ann.detailsList.firstOrNull { it.key == "category" }
                    ?.valueStringList?.firstOrNull()
                cat == "Surveil" || cat == "Mill"
            }
            surveilZt.shouldNotBeNull()

            val category = surveilZt.detailsList
                .firstOrNull { it.key == "category" }
                ?.valueStringList?.firstOrNull()
            category shouldBe "Surveil"

            // affectorId must be set — real server sets it to the ability instance
            // that caused the surveil (Wary Thespian's ETB trigger on the stack).
            // Without this, the client shows the wrong animation.
            surveilZt.affectorId shouldBe surveilZt.affectorId // non-zero check below
            (surveilZt.affectorId != 0).shouldBeTrue()

            // The corresponding ObjectIdChanged should also have the same affectorId
            val surveilOidChanged = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ObjectIdChanged } &&
                    ann.detailsList.any { it.key == "new_id" && it.getValueInt32(0) == surveilZt.affectedIdsList.first() }
            }
            surveilOidChanged.shouldNotBeNull()
            surveilOidChanged.affectorId shouldBe surveilZt.affectorId
        }

        test("surveil keep does not produce ZoneTransfer annotation") {
            val h = setupSurveil(validating = false)
            val snap = h.messageSnapshot()

            h.castSpellByName("Wary Thespian").shouldBeTrue()
            h.passPriority()

            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList

            // Keep on top — no zone transfer should occur for the surveiled card
            h.respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = cardIds)

            val msgs = h.messagesSince(snap)
            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }
            // No ZoneTransfer with Surveil category (card stayed in library)
            val surveilTransfers = allAnnotations.filter { ann ->
                ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                    ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "Surveil" }
            }
            surveilTransfers.size shouldBe 0
        }

        test("surveil state validity") {
            val h = setupSurveil(validating = false)

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
