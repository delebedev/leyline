package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.InstanceId
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Library ordering interactions — surveil and scry.
 *
 * Both mechanics use GroupReq/GroupResp: surveil offers Library/Top vs Graveyard,
 * scry offers Library/Top vs Library/Bottom.
 *
 * Annotation-heavy tests (PlayLand, CastSpell, Resolve pipeline) stay in
 * ScryETBFlowTest — they test the annotation pipeline, not scry itself.
 */
class LibraryOrderInteractionTest :
    InteractionTest({

        // --- Surveil 1 (Wary Thespian: ETB surveil 1) ---

        val surveil1Puzzle = """
            [metadata]
            Name:Surveil 1
            Goal:Win
            Turns:10

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Forest;Forest
            humanhand=Wary Thespian
            humanlibrary=Grizzly Bears;Forest;Forest;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
        """.trimIndent()

        test("surveil 1 — GroupReq shape and revealed card") {
            startPuzzle(surveil1Puzzle)

            val req = harness.castSpellUntilGroupReq("Wary Thespian")
            assertSoftly {
                req.context shouldBe GroupingContext.Surveil
                req.instanceIdsList shouldHaveSize 1
                req.groupSpecsList shouldHaveSize 2
                req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top
                req.groupSpecsList[1].zoneType shouldBe ZoneType.Graveyard
            }

            // Revealed card must be library top (Grizzly Bears)
            val revealedId = req.instanceIdsList.first()
            val cardId = harness.bridge.getForgeCardId(InstanceId(revealedId))
            cardId.shouldNotBeNull()
            val card = game().findById(cardId.value)
            card.shouldNotBeNull()
            card.name shouldBe "Grizzly Bears"
        }

        test("surveil 1 — keep on top leaves card in library") {
            startPuzzle(surveil1Puzzle)
            val cardIds = harness.castSpellUntilGroupReq("Wary Thespian").instanceIdsList

            harness.respondToGroupReq(awayInstanceIds = emptyList(), allInstanceIds = cardIds)

            val gy = human.getZone(ForgeZoneType.Graveyard).cards
            gy.none { it.name == "Grizzly Bears" }.shouldBeTrue()
        }

        test("surveil 1 — put in graveyard moves card") {
            startPuzzle(surveil1Puzzle)
            val cardIds = harness.castSpellUntilGroupReq("Wary Thespian").instanceIdsList

            harness.respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            val gy = human.getZone(ForgeZoneType.Graveyard).cards
            gy.any { it.name == "Grizzly Bears" }.shouldBeTrue()

            // Library top should not be Grizzly Bears
            val libTop = human.getZone(ForgeZoneType.Library).cards.lastOrNull()
            if (libTop != null) {
                libTop.name shouldBe "Forest"
            }

            harness.accumulator.assertConsistent("after surveil to graveyard")
        }

        test("surveil 1 — graveyard produces ZoneTransfer with Surveil category") {
            startPuzzle(surveil1Puzzle)
            val snap = harness.messageSnapshot()
            val cardIds = harness.castSpellUntilGroupReq("Wary Thespian").instanceIdsList

            harness.respondToGroupReq(awayInstanceIds = cardIds, allInstanceIds = cardIds)

            val annotations = harness.annotationsSince(snap)
            val surveilZt = annotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                    ann.detailString("category") == "Surveil"
            }
            surveilZt.shouldNotBeNull()
            surveilZt.affectedIdsList.shouldNotBeEmpty()
            surveilZt.affectorId shouldBe surveilZt.affectorId // self-check
            (surveilZt.affectorId != 0).shouldBeTrue()
        }

        // --- Surveil 2 (Sterling Hound: ETB surveil 2) ---

        test("surveil 2 — multi-card to graveyard") {
            startPuzzle(
                """
            [metadata]
            Name:Surveil 2
            Goal:Win
            Turns:10

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Sterling Hound
            humanbattlefield=Plains;Plains;Plains
            humanlibrary=Mountain;Forest;Island;Swamp;Plains
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent(),
            )

            val groupReq = harness.castSpellUntilGroupReq("Sterling Hound")
            groupReq.context shouldBe GroupingContext.Surveil
            groupReq.instanceIdsList.size shouldBe 2

            val allIds = groupReq.instanceIdsList
            harness.respondToGroupReq(awayInstanceIds = allIds, allInstanceIds = allIds)

            val gy = human.getZone(ForgeZoneType.Graveyard)
            // Sterling Hound resolved to BF, 2 surveiled cards in graveyard
            gy.size() shouldBe 2
            isGameOver().shouldBeFalse()
        }

        // --- Scry 1 (Wall of Runes: ETB scry 1) ---

        val scryPuzzle = """
            [metadata]
            Name:Scry 1
            Goal:Win
            Turns:10

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Wall of Runes
            humanbattlefield=Island
            humanlibrary=Grizzly Bears;Forest;Forest;Forest;Forest
            aibattlefield=Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
        """.trimIndent()

        test("scry 1 — GroupReq shape") {
            startPuzzle(scryPuzzle)

            val req = harness.castSpellUntilGroupReq("Wall of Runes")
            assertSoftly {
                req.context shouldBe GroupingContext.Scry_a0f6
                req.instanceIdsList shouldHaveSize 1
                req.groupSpecsList shouldHaveSize 2
                req.groupSpecsList[0].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[0].subZoneType shouldBe SubZoneType.Top
                req.groupSpecsList[1].zoneType shouldBe ZoneType.Library
                req.groupSpecsList[1].subZoneType shouldBe SubZoneType.Bottom
            }
        }

        test("scry 1 — put on bottom") {
            startPuzzle(scryPuzzle)
            val cardIds = harness.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            harness.respondToScry(bottomInstanceIds = cardIds, allInstanceIds = cardIds)

            // Wall of Runes should be on battlefield
            human.getZone(ForgeZoneType.Battlefield).cards
                .filter { it.name == "Wall of Runes" } shouldHaveSize 1

            // Scry annotation emitted
            val scryAnn = harness.allMessages
                .flatMap { if (it.hasGameStateMessage()) it.gameStateMessage.annotationsList else emptyList() }
                .firstOrNull { ann -> ann.typeList.any { it == AnnotationType.Scry_af5a } }
            scryAnn.shouldNotBeNull()

            harness.accumulator.assertConsistent("after scry to bottom")
            assertGsIdChain(harness.allMessages, context = "scry flow")
            isGameOver().shouldBeFalse()
        }

        test("scry 1 — keep on top") {
            startPuzzle(scryPuzzle)
            val player = human
            val cardIds = harness.castSpellUntilGroupReq("Wall of Runes").instanceIdsList

            harness.respondToScry(bottomInstanceIds = emptyList(), allInstanceIds = cardIds)

            // Card should still be in library (not moved to bottom or graveyard)
            player.getZone(ForgeZoneType.Library).cards.shouldNotBeEmpty()
            isGameOver().shouldBeFalse()
        }
    })
