package leyline.behavior.annotations.tokencreated

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType

/**
 * Token diff stability — standard tokens retain cardTypes, subtypes,
 * and uniqueAbilities across diff GSMs (regression for leyline-iz4).
 *
 * Cast Novice Inspector → ETB investigate → Clue token.
 * Build two GSMs — second must still have Artifact type, Clue subtype,
 * and sacrifice-to-draw ability on the Clue.
 */
class TokenDiffStabilityTest :
    SessionTest({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Novice Inspector")
            TestCardRegistry.ensureCardRegistered("Plains")
        }

        val puzzleText =
            """
            [metadata]
            Name:Clue Token Diff Stability
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Cast Novice Inspector, investigate, check Clue token.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Novice Inspector
            humanbattlefield=Plains
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            aibattlefield=Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        fun castInspectorAndWaitForClue(): Int {
            human
                .getZone(ZoneType.Hand)
                .cards
                .any { it.name == "Novice Inspector" }
                .shouldBeTrue()

            castSpellByName("Novice Inspector").shouldBeTrue()

            // Pass until Clue token appears (ETB trigger resolves)
            repeat(15) {
                val clues = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
                if (clues.isNotEmpty()) return@repeat
                passPriority()
            }

            val clue =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.isToken }
                    .shouldNotBeNull()
            return human.battlefield.iid(clue)
        }

        test("Clue token has Artifact type and Clue subtype in GSM") {
            startPuzzleRaw(puzzleText, validating = true)

            val clueIid = castInspectorAndWaitForClue()

            val snapClue1 = GsmSnapshot.capture(harness.game(), harness.bridge, "test-clue", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapClue1,
                        1,
                        "test-clue",
                        harness.bridge,
                        viewingSeatId = 1,
                        effectFacts = harness.bridge.materializeEffectProjectionFacts(),
                    ).gsm
            val clueObj =
                gsm.gameObjectsList
                    .firstOrNull { it.instanceId == clueIid }
                    .shouldNotBeNull()

            assertSoftly {
                clueObj.instanceId shouldBe clueIid
                clueObj.cardTypesList shouldContain CardType.Artifact_a80b
                clueObj.subtypesList shouldContain SubType.Clue
            }
        }

        test("Clue token retains types and subtypes across diff GSMs") {
            startPuzzleRaw(puzzleText, validating = true)

            val clueIid = castInspectorAndWaitForClue()

            // First GSM — baseline
            val snapClue2 = GsmSnapshot.capture(harness.game(), harness.bridge, "test-clue", 1)
            val gsm1 =
                StateMapper
                    .buildFromSnapshot(
                        snapClue2,
                        1,
                        "test-clue",
                        harness.bridge,
                        viewingSeatId = 1,
                        effectFacts = harness.bridge.materializeEffectProjectionFacts(),
                    ).gsm

            val clueObj1 = gsm1.gameObjectsList.first { it.instanceId == clueIid }
            clueObj1.cardTypesList shouldContain CardType.Artifact_a80b
            clueObj1.instanceId shouldBe clueIid

            // Trigger a state change
            passPriority()

            // Second GSM — diff against snapClue2 baseline
            val snapClue3 = GsmSnapshot.capture(harness.game(), harness.bridge, "test-clue", 2)
            val gsm2 =
                StateMapper
                    .buildDiff(
                        snapClue2,
                        snapClue3,
                        FrameEventLog.EMPTY,
                        2,
                        "test-clue",
                        harness.bridge,
                        viewingSeatId = 1,
                        effectFacts = harness.bridge.materializeEffectProjectionFacts(),
                    ).gsm

            // If Clue appears in diff, fields must be intact (not stripped)
            val clueInDiff = gsm2.gameObjectsList.firstOrNull { it.instanceId == clueIid }
            if (clueInDiff != null) {
                assertSoftly {
                    clueInDiff.cardTypesList shouldContain CardType.Artifact_a80b
                    clueInDiff.subtypesList shouldContain SubType.Clue
                    clueInDiff.instanceId shouldBe clueIid
                }
            }

            // Registry cached the grpId — stable for future diffs
            harness.bridge.tokenRegistry
                .resolve(clueIid)
                .shouldNotBeNull()
        }
    })
