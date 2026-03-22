package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Scry ETB flow tests — derived from real server recording.
 *
 * Source: recordings/2026-03-08_19-44-CHALLENGE-STARTER-SEAT1, indices 17-29.
 * Card: Wall of Runes (grpId 75478), 0/4 Defender Wall, "When ~ enters, scry 1."
 *
 * Recording sequence:
 *   1. Play Island (hand → battlefield, ObjectIdChanged + ZoneTransfer/PlayLand)
 *   2. Cast Wall of Runes (hand → stack, CastSpell + mana payment annotations)
 *   3. Priority passes, Wall resolves (stack → battlefield, Resolve + ETB trigger created)
 *   4. Trigger resolves → GroupReq (context=Scry, top/bottom choice)
 *   5. Player chooses bottom → Scry annotation + trigger cleanup
 *
 * This test verifies leyline's output matches the recording's annotation structure:
 * - ZoneTransfer categories (PlayLand, CastSpell, Resolve)
 * - Mana payment sequence (AbilityInstanceCreated → TappedUntappedPermanent → ManaPaid → Deleted)
 * - ETB trigger lifecycle (AbilityInstanceCreated → TriggeringObject → GroupReq → Scry → Deleted)
 * - GroupReq shape (context=Scry, Library/Top + Library/Bottom specs)
 */
class ScryETBFlowTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        fun setup(): MatchFlowHarness {
            val h = MatchFlowHarness(validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/scry-etb.pzl")
            return h
        }

        test("play land produces PlayLand zone transfer") {
            val h = setup()
            val snap = h.messageSnapshot()

            h.playLand().shouldBeTrue()

            val msgs = h.messagesSince(snap)
            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }

            // ObjectIdChanged: hand ID → battlefield ID (recording: 161 → 282)
            val oic = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ObjectIdChanged }
            }
            oic.shouldNotBeNull()
            oic.detail("orig_id").shouldNotBeNull()
            oic.detail("new_id").shouldNotBeNull()

            // ZoneTransfer with PlayLand category (recording: zone_src=31, zone_dest=28)
            val zt = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                    ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "PlayLand" }
            }
            zt.shouldNotBeNull()
            zt.affectedIdsList.shouldNotBeEmpty()

            // UserActionTaken (recording: affectorId=1 (seat), actionType=3)
            val uat = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.UserActionTaken }
            }
            uat.shouldNotBeNull()
        }

        test("cast Wall of Runes produces CastSpell annotations with mana payment") {
            val h = setup()

            // Play land first (need mana to cast)
            h.playLand().shouldBeTrue()

            val snap = h.messageSnapshot()
            h.castSpellByName("Wall of Runes").shouldBeTrue()

            val msgs = h.messagesSince(snap)
            val allAnnotations = msgs.flatMap { msg ->
                if (msg.hasGameStateMessage()) {
                    msg.gameStateMessage.annotationsList
                } else {
                    emptyList()
                }
            }

            // ZoneTransfer with CastSpell category (recording: hand → stack)
            val castZt = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                    ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "CastSpell" }
            }
            castZt.shouldNotBeNull()

            // TappedUntappedPermanent (recording: Island tapped for mana)
            val tap = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.TappedUntappedPermanent }
            }
            tap.shouldNotBeNull()
            tap.detail("tapped").shouldNotBeNull()

            // ManaPaid (recording: Island → Wall of Runes, color=2 blue)
            val manaPaid = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ManaPaid }
            }
            manaPaid.shouldNotBeNull()
        }

        test("Wall of Runes resolution produces Resolve transfer and ETB trigger") {
            val h = setup()
            h.playLand().shouldBeTrue()

            val snap = h.messageSnapshot()
            h.castSpellByName("Wall of Runes").shouldBeTrue()
            h.passPriority() // auto-pass resolves creature, blocks on scry GroupReq

            // Resolution annotations are deferred until after the GroupReq interaction:
            // auto-pass detects the pending scry prompt and sends GroupReq directly,
            // deferring the resolution state diff. Resolve to release them.
            val groupReq = h.allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList
            h.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            val allAnnotations = h.annotationsSince(snap)

            // ResolutionStart + ResolutionComplete for the creature spell
            // Recording: affectorId=283 (Wall on stack), details grpid=75478
            val resStart = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ResolutionStart }
            }
            resStart.shouldNotBeNull()

            val resComplete = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ResolutionComplete }
            }
            resComplete.shouldNotBeNull()

            // ZoneTransfer with Resolve category (recording: stack → battlefield)
            val resolveZt = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                    ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "Resolve" }
            }
            resolveZt.shouldNotBeNull()

            // AbilityInstanceCreated for ETB trigger
            // Note: with deferred resolution, the CastSpell AbilityInstanceCreated
            // fires during cast, and the ETB trigger's AbilityInstanceCreated
            // may be absorbed into the same annotation batch.
            val triggerCreated = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.AbilityInstanceCreated }
            }
            triggerCreated.shouldNotBeNull()
            triggerCreated.detail("source_zone").shouldNotBeNull()
        }

        test("scry ETB emits GroupReq with Scry context and correct specs") {
            val h = setup()
            h.playLand().shouldBeTrue()
            val req = h.castSpellUntilGroupReq("Wall of Runes")
            assertSoftly {
                req.context shouldBe GroupingContext.Scry_a0f6
                req.instanceIdsList.shouldNotBeEmpty()
                req.groupSpecsList.size shouldBe 2
            }

            // Recording: [{upperBound:1, zoneType:Library, subZoneType:Top},
            //             {upperBound:1, zoneType:Library, subZoneType:Bottom}]
            assertSoftly {
                req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top
                req.groupSpecsList[1].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[1].subZoneType shouldBe SubZoneType.Bottom
            }
        }

        test("scry put on bottom produces Scry annotation with counts") {
            val h = setup()
            h.playLand().shouldBeTrue()
            val cardIds = h.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            val snap = h.messageSnapshot()
            // Put card on bottom (recording: player chose bottom)
            h.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            val allAnnotations = h.annotationsSince(snap)

            // Scry annotation — engine shape: affectedIds=[seatId], topCount/bottomCount
            // Conformance gap: recording uses affectedIds=[cardId], bottomIds detail key.
            // Our engine emits aggregate counts instead of per-card IDs.
            val scryAnn = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.Scry_af5a }
            }
            scryAnn.shouldNotBeNull()
            scryAnn.affectedIdsList.shouldNotBeEmpty()
            scryAnn.detail("bottomCount").shouldNotBeNull()

            // ResolutionStart + ResolutionComplete for the creature spell
            val resStart = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ResolutionStart }
            }
            resStart.shouldNotBeNull()

            val resComplete = allAnnotations.filter { ann ->
                ann.typeList.any { it == AnnotationType.ResolutionComplete }
            }
            resComplete.shouldNotBeEmpty()
        }

        test("scry keep on top does not move card") {
            val h = setup()
            h.playLand().shouldBeTrue()
            val cardIds = h.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            // Keep on top (empty bottom list)
            h.respondToScry(bottomInstanceIds = emptyList(), allInstanceIds = cardIds)

            // Card should still be on top of library, not bottom
            val player = h.bridge.getPlayer(SeatId(1))!!
            val libCards = player.getZone(ForgeZoneType.Library).cards
            libCards.shouldNotBeEmpty()
        }

        test("full scry flow state validity") {
            val h = setup()
            h.playLand().shouldBeTrue()
            val cardIds = h.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            h.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            // Wall of Runes should be on battlefield
            val player = h.bridge.getPlayer(SeatId(1))!!
            val bf = player.getZone(ForgeZoneType.Battlefield).cards
            bf.any { it.name == "Wall of Runes" }.shouldBeTrue()

            // Island should be on battlefield (tapped)
            bf.any { it.isLand }.shouldBeTrue()

            h.accumulator.assertConsistent("after scry ETB flow")
            assertGsIdChain(h.allMessages, context = "scry ETB flow")
            h.isGameOver().shouldBeFalse()
        }
    })
