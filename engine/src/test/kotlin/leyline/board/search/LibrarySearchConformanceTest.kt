package leyline.board.search

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.SuperType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Conformance test: library card objects during search.
 *
 * Compares the shape of GameObjectInfo we produce for library cards
 * against the known wire shape from reference sessions:
 *
 * Session: 2026-03-21_22-05-00, frame 104 (gsId 52)
 * Card: Forest (basic land) in library during Bushwhack search
 *
 * Expected protocol shape for basic land in library:
 *   type=Card, visibility=Private, viewers=[ownerSeatId],
 *   superTypes=[Basic], cardTypes=[Land], subtypes=[Forest],
 *   uniqueAbilityCount=1, owner=seatId, controller=seatId
 *   NO power, NO toughness, NO name, NO overlayGrpId, NO color
 */
class LibrarySearchConformanceTest :
    BoardTest({

        test("library reveal GSM: basic land object shape matches reference") {
            val puzzleText =
                """
                [metadata]
                Name:Library Search Conformance
                Goal:Win
                Turns:1
                Description:Minimal board for library reveal test.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Sylvan Ranger
                humanbattlefield=Forest;Forest
                humanlibrary=Mountain;Mountain
                aibattlefield=Forest
                ailibrary=Forest
                """.trimIndent()

            val (bridge, game, counter) = startPuzzleAtMain1(puzzleText)

            // Simulate what TargetingHandler.sendSearchReq does:
            // reveal library for seat 1, then build a GSM
            val gsId1 = counter.nextGsId()
            val snapLib1 = GsmSnapshot.capture(game, bridge, Board.TEST_MATCH_ID, gsId1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapLib1,
                        gsId1,
                        Board.TEST_MATCH_ID,
                        bridge,
                        viewingSeatId = 1,
                        revealForSeat = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                    ).gsm

            // Find library objects (seat 1 library = zone 32)
            val libraryObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.P1_LIBRARY }
            libraryObjects.shouldNotBeEmpty()

            // Find a Mountain (basic land)
            val mountain =
                libraryObjects.first { obj ->
                    obj.cardTypesList.contains(CardType.Land_a80b)
                }

            // --- Field-by-field comparison against reference ---

            assertSoftly {
                // Structure
                mountain.type shouldBe GameObjectType.Card
                mountain.visibility shouldBe Visibility.Private
                mountain.viewersList shouldBe listOf(1)
                mountain.ownerSeatId shouldBe 1
                mountain.controllerSeatId shouldBe 1
                mountain.zoneId shouldBe ZoneIds.P1_LIBRARY

                // Card identity
                mountain.cardTypesList shouldBe listOf(CardType.Land_a80b)
                mountain.superTypesList shouldBe listOf(SuperType.Basic)
                mountain.subtypesList.map { it.name } shouldBe listOf("Mountain")

                mountain.uniqueAbilitiesList.size shouldBe 1
                mountain.hasPower() shouldBe false
                mountain.hasToughness() shouldBe false
            }

            // Write full object fields to file for diff against reference
            val outputDir = java.io.File("build/conformance").also { it.mkdirs() }
            val sb = StringBuilder()
            sb.appendLine("=== Engine Mountain object (library reveal) ===")
            for (field in mountain.allFields) {
                sb.appendLine("  ${field.key.name} = ${field.value}")
            }
            sb.appendLine()
            sb.appendLine("=== All library objects summary ===")
            for (obj in libraryObjects) {
                val fields =
                    obj.allFields.keys
                        .map { it.name }
                        .sorted()
                sb.appendLine("  instanceId=${obj.instanceId} grpId=${obj.grpId} fields=$fields")
            }
            outputDir.resolve("library-search-objects.txt").writeText(sb.toString())
        }

        test("library reveal GSM: all library objects have correct base shape") {
            val puzzleText =
                """
                [metadata]
                Name:Library Search Shape
                Goal:Win
                Turns:1
                Description:Verify all library card objects.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Sylvan Ranger
                humanbattlefield=Forest;Forest
                humanlibrary=Mountain;Mountain
                aibattlefield=Forest
                ailibrary=Forest
                """.trimIndent()

            val (bridge, game, counter) = startPuzzleAtMain1(puzzleText)

            val gsId2 = counter.nextGsId()
            val snapLib2 = GsmSnapshot.capture(game, bridge, Board.TEST_MATCH_ID, gsId2)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapLib2,
                        gsId2,
                        Board.TEST_MATCH_ID,
                        bridge,
                        viewingSeatId = 1,
                        revealForSeat = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                    ).gsm

            val libraryObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.P1_LIBRARY }

            for (obj in libraryObjects) {
                assertSoftly {
                    obj.type shouldBe GameObjectType.Card
                    obj.visibility shouldBe Visibility.Private
                    obj.viewersList shouldBe listOf(1)
                    obj.ownerSeatId shouldBe 1
                    obj.controllerSeatId shouldBe 1
                    obj.zoneId shouldBe ZoneIds.P1_LIBRARY
                    obj.cardTypesList.shouldNotBeEmpty()
                }
            }
        }
    })
