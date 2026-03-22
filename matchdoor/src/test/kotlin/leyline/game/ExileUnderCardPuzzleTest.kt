package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.conformance.MatchFlowHarness
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
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Banishing Light exile emits DisplayCardUnderCard, Disenchant removes it") {
            val pzl = """
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
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)
            h.phase() shouldBe "MAIN1"

            // Find Grizzly Bears instance ID (AI's battlefield)
            val ai = h.game().registeredPlayers.last()
            val bears = ai.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val bearsIid = h.bridge.getOrAllocInstanceId(ForgeCardId(bears.id)).value

            // --- Phase 1: Cast Banishing Light, target Grizzly Bears ---
            val snap1 = h.messageSnapshot()
            h.castSpellByName("Banishing Light").shouldBeTrue()

            // Banishing Light is on the stack — pass to resolve it
            // ETB trigger will fire and need a target
            h.passPriority() // resolve Banishing Light → ETB trigger on stack

            // Select Grizzly Bears as target for the exile trigger
            h.selectTargets(listOf(bearsIid))

            // Pass until the trigger resolves and Grizzly Bears is exiled
            h.passUntil(maxPasses = 10) {
                ai.getZone(ZoneType.Battlefield).cards.none { it.name == "Grizzly Bears" }
            }.shouldBeTrue()

            // Verify Grizzly Bears is in exile
            ai.getZone(ZoneType.Exile).cards.any { it.name == "Grizzly Bears" }.shouldBeTrue()

            // Check persistent annotations for DisplayCardUnderCard
            val gsms = h.gameStateMessagesSince(snap1)
            gsms.size shouldBeGreaterThan 0

            val lastGsm = gsms.last()
            val underCardAnns = lastGsm.persistentAnnotationsList
                .filter { it.typeList.any { t -> t == AnnotationType.DisplayCardUnderCard } }

            underCardAnns.size shouldBe 1
            // Resolve Banishing Light iid (on battlefield, stable at this point)
            val human = h.game().registeredPlayers.first()
            val banishing = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Banishing Light" }
            val banishingIid = h.bridge.getOrAllocInstanceId(ForgeCardId(banishing.id)).value
            underCardAnns[0].affectorId shouldBe banishingIid
            underCardAnns[0].affectedIdsCount shouldBe 1

            // --- Phase 2: Cast Disenchant to destroy Banishing Light ---

            val snap2 = h.messageSnapshot()
            h.castSpellByName("Disenchant").shouldBeTrue()

            // Disenchant targets Banishing Light
            h.selectTargets(listOf(banishingIid))

            // Pass to resolve — Banishing Light destroyed, Grizzly Bears returns
            h.passUntil(maxPasses = 15) {
                ai.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
            }.shouldBeTrue()

            // Verify Grizzly Bears is back on battlefield
            ai.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }.shouldBeTrue()

            // Verify DisplayCardUnderCard annotation is removed
            val gsms2 = h.gameStateMessagesSince(snap2)
            val lastGsm2 = gsms2.last()
            val remainingUnderCard = lastGsm2.persistentAnnotationsList
                .filter { it.typeList.any { t -> t == AnnotationType.DisplayCardUnderCard } }
            remainingUnderCard.shouldBeEmpty()
        }
    })
