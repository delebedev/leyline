package leyline.behavior.annotations.displaycardundercard

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Integration test for DisplayCardUnderCard annotation lifecycle.
 *
 * Puzzle: Cast Banishing Light to exile Grizzly Bears, verify persistent
 * DisplayCardUnderCard annotation appears. Then cast Disenchant to destroy
 * the Banishing Light, verify the annotation is removed and the creature
 * returns to play.
 */
class ExileUnderCardPuzzleTest :
    SessionTest({
        session(
            "Banishing Light exile emits DisplayCardUnderCard, Disenchant removes it",
            """
            [metadata]
            Name:Exile Under Card
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:DisplayCardUnderCard lifecycle test.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=1

            humanhand=Banishing Light;Disenchant
            humanbattlefield=Plains;Plains;Plains;Plains;Plains
            humanlibrary=Plains
            aibattlefield=Grizzly Bears
            ailibrary=Forest
            """.trimIndent(),
            validating = true,
        ) {
            phase() shouldBe "MAIN1"

            val bearsIid = ai.battlefield.iid("Grizzly Bears")

            // --- Phase 1: Cast Banishing Light, target Grizzly Bears ---
            val phase1 =
                after {
                    castSpellByName("Banishing Light").shouldBeTrue()

                    // Banishing Light is on the stack — pass to resolve it
                    // ETB trigger will fire and need a target
                    passPriority() // resolve Banishing Light → ETB trigger on stack

                    // Select Grizzly Bears as target for the exile trigger
                    selectTargets(listOf(bearsIid))

                    // Pass until the trigger resolves and Grizzly Bears is exiled
                    passUntil(maxPasses = 10) {
                        ai.getZone(ZoneType.Battlefield).cards.none { it.name == "Grizzly Bears" }
                    }.shouldBeTrue()
                }

            // Verify Grizzly Bears is in exile
            ai
                .getZone(ZoneType.Exile)
                .cards
                .any { it.name == "Grizzly Bears" }
                .shouldBeTrue()

            // Check persistent annotations for DisplayCardUnderCard.
            // Pick the latest GSM that actually carries the persistent
            // annotation — the trailing post-content echo GSM has no
            // persistent annotations, so `gsms.last()` would hit the empty
            // echo and report 0.
            val gsms = phase1.messages.mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
            gsms.size shouldBeGreaterThan 0

            val underCardAnns =
                gsms
                    .flatMap { it.persistentAnnotationsList }
                    .filter { it.typeList.any { t -> t == AnnotationType.DisplayCardUnderCard } }

            underCardAnns.size shouldBe 1
            // Resolve Banishing Light iid (on battlefield, stable at this point)
            val banishingIid = human.battlefield.iid("Banishing Light")
            underCardAnns[0].affectorId shouldBe banishingIid
            underCardAnns[0].affectedIdsCount shouldBe 1

            // --- Phase 2: Cast Disenchant to destroy Banishing Light ---

            val phase2 =
                after {
                    castSpellByName("Disenchant").shouldBeTrue()

                    // Disenchant targets Banishing Light
                    selectTargets(listOf(banishingIid))

                    // Pass to resolve — Banishing Light destroyed, Grizzly Bears returns
                    passUntil(maxPasses = 15) {
                        ai.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
                    }.shouldBeTrue()
                }

            // Verify Grizzly Bears is back on battlefield
            ai
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Grizzly Bears" }
                .shouldBeTrue()

            // Verify DisplayCardUnderCard annotation is removed
            val gsms2 = phase2.messages.mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
            val lastGsm2 = gsms2.last()
            val remainingUnderCard =
                lastGsm2.persistentAnnotationsList
                    .filter { it.typeList.any { t -> t == AnnotationType.DisplayCardUnderCard } }
            remainingUnderCard.shouldBeEmpty()
        }
    })
