package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.assertGsIdChain
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Scry ETB flow tests — derived from a reference run.
 *
 * Source: local reference run 2026-03-08, indices 17-29.
 * Card: Wall of Runes (grpId 75478), 0/4 Defender Wall, "When ~ enters, scry 1."
 *
 * Reference sequence:
 *   1. Play Island (hand → battlefield, ObjectIdChanged + ZoneTransfer/PlayLand)
 *   2. Cast Wall of Runes (hand → stack, CastSpell + mana payment annotations)
 *   3. Priority passes, Wall resolves (stack → battlefield, Resolve + ETB trigger created)
 *   4. Trigger resolves → GroupReq (context=Scry, top/bottom choice)
 *   5. Player chooses bottom → Scry annotation + trigger cleanup
 *
 * This test verifies leyline's output matches the reference annotation structure:
 * - ZoneTransfer categories (PlayLand, CastSpell, Resolve)
 * - Mana payment sequence (AbilityInstanceCreated → TappedUntappedPermanent → ManaPaid → Deleted)
 * - ETB trigger lifecycle (AbilityInstanceCreated → TriggeringObject → GroupReq → Scry → Deleted)
 * - GroupReq shape (context=Scry, Library/Top + Library/Bottom specs)
 */
class ScryETBFlowTest :
    SessionTest({

        fun startScryEtb() = startPuzzleFile("puzzles/scry-etb.pzl", validating = true)

        test("play land produces PlayLand zone transfer") {
            startScryEtb()

            val msgs = after { playLand().shouldBeTrue() }.messages
            val allAnnotations =
                msgs.flatMap { msg ->
                    if (msg.hasGameStateMessage()) {
                        msg.gameStateMessage.annotationsList
                    } else {
                        emptyList()
                    }
                }

            // ObjectIdChanged: hand ID → battlefield ID (reference: 161 → 282)
            val oic =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ObjectIdChanged }
                }
            assertSoftly {
                oic.shouldNotBeNull()
                oic.detail("orig_id").shouldNotBeNull()
                oic.detail("new_id").shouldNotBeNull()
            }

            // ZoneTransfer with PlayLand category (reference: zone_src=31, zone_dest=28)
            val zt =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                        ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "PlayLand" }
                }
            val uat =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.UserActionTaken }
                }
            assertSoftly {
                allAnnotations.count { ann -> ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } } shouldBe 1
                zt.shouldNotBeNull()
                zt.affectedIdsList.shouldNotBeEmpty()
                uat.shouldNotBeNull()
            }
        }

        test("cast Wall of Runes produces CastSpell annotations with mana payment") {
            startScryEtb()

            // Play land first (need mana to cast)
            playLand().shouldBeTrue()

            val msgs = after { castSpellByName("Wall of Runes").shouldBeTrue() }.messages
            val allAnnotations =
                msgs.flatMap { msg ->
                    if (msg.hasGameStateMessage()) {
                        msg.gameStateMessage.annotationsList
                    } else {
                        emptyList()
                    }
                }

            // ZoneTransfer with CastSpell category (reference: hand → stack)
            val castZt =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                        ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "CastSpell" }
                }
            val tap =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.TappedUntappedPermanent }
                }
            val manaPaid =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ManaPaid }
                }
            assertSoftly {
                castZt.shouldNotBeNull()
                tap.shouldNotBeNull()
                tap.detail("tapped").shouldNotBeNull()
                manaPaid.shouldNotBeNull()
                manaPaid.detailInt("color") shouldBe ManaColor.Blue_afc9.number
            }
        }

        test("Wall of Runes resolution produces Resolve transfer and ETB trigger") {
            startScryEtb()
            playLand().shouldBeTrue()

            val snap = messageSnapshot()
            castSpellByName("Wall of Runes").shouldBeTrue()
            passPriority() // auto-pass resolves creature, blocks on scry GroupReq

            // Resolution annotations are deferred until after the GroupReq interaction:
            // auto-pass detects the pending scry prompt and sends GroupReq directly,
            // deferring the resolution state diff. Resolve to release them.
            val groupReq = allMessages.last { it.hasGroupReq() }
            val cardIds = groupReq.groupReq.instanceIdsList
            harness.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            val allAnnotations = annotationsSince(snap)

            // ResolutionStart + ResolutionComplete for the creature spell
            // Expected: affectorId=283 (Wall on stack), details grpid=75478
            val resStart =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ResolutionStart }
                }
            val resComplete =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ResolutionComplete }
                }
            val resolveZt =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                        ann.detailsList.any { it.key == "category" && it.valueStringList.firstOrNull() == "Resolve" }
                }
            val triggerCreated =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.AbilityInstanceCreated }
                }
            assertSoftly {
                resStart.shouldNotBeNull()
                resComplete.shouldNotBeNull()
                resolveZt.shouldNotBeNull()
                resolveZt.detailString("category") shouldBe "Resolve"
                triggerCreated.shouldNotBeNull()
                triggerCreated.detail("source_zone").shouldNotBeNull()
            }
        }

        test("scry ETB emits GroupReq with Scry context and correct specs") {
            startScryEtb()
            playLand().shouldBeTrue()
            val req = harness.castSpellUntilGroupReq("Wall of Runes")
            assertSoftly {
                req.context shouldBe GroupingContext.Scry_a0f6
                req.instanceIdsList.shouldNotBeEmpty()
                req.groupSpecsList.size shouldBe 2
            }

            // Expected: [{upperBound:1, zoneType:Library, subZoneType:Top},
            //             {upperBound:1, zoneType:Library, subZoneType:Bottom}]
            assertSoftly {
                req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top
                req.groupSpecsList[1].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[1].subZoneType shouldBe SubZoneType.Bottom
            }
        }

        test("scry put on bottom produces Scry annotation with card ids") {
            startScryEtb()
            playLand().shouldBeTrue()
            val cardIds = harness.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            val allAnnotations =
                after {
                    // Put card on bottom (reference: player chose bottom)
                    harness.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)
                }.messages.flatMap { msg ->
                    if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList()
                }

            // Scry annotation records the card ids chosen for top/bottom groups.
            val scryAnn =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.Scry_af5a }
                }
            assertSoftly {
                scryAnn.shouldNotBeNull()
                scryAnn.affectedIdsList shouldBe cardIds
                scryAnn.detailIntList("topIds") shouldBe emptyList()
                scryAnn.detailIntList("bottomIds") shouldBe cardIds
            }

            // ResolutionStart + ResolutionComplete for the creature spell
            val resStart =
                allAnnotations.firstOrNull { ann ->
                    ann.typeList.any { it == AnnotationType.ResolutionStart }
                }
            resStart.shouldNotBeNull()

            val resComplete =
                allAnnotations.filter { ann ->
                    ann.typeList.any { it == AnnotationType.ResolutionComplete }
                }
            resComplete.shouldNotBeEmpty()
        }

        test("scry keep on top does not move card") {
            startScryEtb()
            playLand().shouldBeTrue()
            val cardIds = harness.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            // Keep on top (empty bottom list)
            harness.respondToScry(bottomInstanceIds = emptyList(), allInstanceIds = cardIds)

            // Card should still be on top of library, not bottom
            val libCards = human.getZone(ForgeZoneType.Library).cards
            assertSoftly {
                libCards.shouldNotBeEmpty()
                libCards.first().name shouldBe "Island"
            }
        }

        test("ETB trigger emits TriggeringObject persistent annotation, deleted on resolve") {
            startScryEtb()
            playLand().shouldBeTrue()

            val snap = messageSnapshot()
            val req = harness.castSpellUntilGroupReq("Wall of Runes")
            val triggerMessages = messagesSince(snap)

            // The ETB trigger enters the stack before GroupReq, then the scry
            // response resolves and deletes that same persistent annotation.
            val resolveMessages =
                after {
                    harness.respondToScry(bottomInstanceIds = req.instanceIdsList, allInstanceIds = req.instanceIdsList)
                }.messages

            val triggering =
                triggerMessages
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .firstOrNull()
            triggering.shouldNotBeNull()
            assertSoftly {
                triggering.affectedIdsList.shouldNotBeEmpty()
                // Wall of Runes' ETB triggers from the battlefield — assert the
                // value, not just presence, so the snap-diff path's accurate
                // sourceZoneId doesn't silently regress to the event-path
                // BATTLEFIELD-fallback (leyline-w9ij).
                triggering.detailInt("source_zone") shouldBe leyline.game.mapping.ZoneIds.BATTLEFIELD
            }

            val deletedIds =
                resolveMessages
                    .deletedPersistentAnnotationIds()
            (triggering.id in deletedIds).shouldBeTrue()
        }

        test("full scry flow state validity") {
            startScryEtb()
            playLand().shouldBeTrue()
            val cardIds = harness.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            harness.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            // Wall of Runes should be on battlefield
            val bf = human.getZone(ForgeZoneType.Battlefield).cards
            assertSoftly {
                bf.any { it.name == "Wall of Runes" }.shouldBeTrue()
                bf.any { it.isLand }.shouldBeTrue()
                harness.accumulator.assertConsistent("after scry ETB flow")
                assertGsIdChain(allMessages, context = "scry ETB flow")
                isGameOver().shouldBeFalse()
            }
        }
    })
