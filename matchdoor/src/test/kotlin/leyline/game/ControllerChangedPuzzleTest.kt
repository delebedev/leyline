package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.conformance.MatchFlowHarness
import leyline.conformance.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Integration test for ControllerChanged annotation lifecycle.
 *
 * Puzzle: Cast Act of Treason on opponent's Grizzly Bears, steal it,
 * attack for lethal. Verifies:
 * - Transient ControllerChanged annotation on steal
 * - Persistent ControllerChanged+LayeredEffect with effect_id
 * - LayeredEffectCreated transient
 */
class ControllerChangedPuzzleTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Act of Treason steals creature, annotations emitted, attack wins") {
            val pzl = """
            [metadata]
            Name:Steal Creature
            Goal:Win
            Turns:1
            Difficulty:Tutorial
            Description:Cast Act of Treason, steal Grizzly Bears, attack for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=2

            humanhand=Act of Treason
            humanbattlefield=Mountain;Mountain;Mountain
            humanlibrary=Mountain
            aibattlefield=Grizzly Bears
            ailibrary=Forest
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)
            h.phase() shouldBe "MAIN1"

            // Find Grizzly Bears instanceId
            val ai = h.game().registeredPlayers.last()
            val bears = ai.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val bearsIid = h.bridge.getOrAllocInstanceId(ForgeCardId(bears.id)).value

            val snap = h.messageSnapshot()

            // Cast Act of Treason targeting Grizzly Bears
            h.castSpellByName("Act of Treason").shouldBeTrue()
            h.selectTargets(listOf(bearsIid))

            // Pass until Act of Treason resolves and Bears changes controller
            val human = h.game().registeredPlayers.first()
            h.passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
            }.shouldBeTrue()

            // Check annotations from steal
            val gsms = h.gameStateMessagesSince(snap)
            gsms.size shouldBeGreaterThan 0

            // Find ControllerChanged transient annotation
            val allAnnotations = gsms.flatMap { it.annotationsList }
            val ccTransient = allAnnotations
                .filter { it.typeList.any { t -> t == AnnotationType.ControllerChanged } }
            ccTransient.size shouldBeGreaterThan 0

            // Find LayeredEffectCreated transient
            val lecTransient = allAnnotations
                .filter { it.typeList.any { t -> t == AnnotationType.LayeredEffectCreated } }
            lecTransient.size shouldBeGreaterThan 0

            // Check persistent ControllerChanged+LayeredEffect
            val lastGsm = gsms.last()
            val ccPersistent = lastGsm.persistentAnnotationsList
                .filter {
                    it.typeList.any { t -> t == AnnotationType.ControllerChanged } &&
                        it.typeList.any { t -> t == AnnotationType.LayeredEffect }
                }

            assertSoftly {
                ccPersistent.size shouldBe 1
                ccPersistent[0].detailInt("effect_id") shouldBeGreaterThan 7000
            }

            // Now attack with the stolen creature and win
            h.advanceToCombat()
            val bearsNewIid = h.bridge.getOrAllocInstanceId(ForgeCardId(bears.id)).value
            h.declareAttackers(listOf(bearsNewIid))

            // Pass through combat — AI at 2 life, Bears is 2/2, should be lethal
            h.passUntil(maxPasses = 20) {
                h.isGameOver()
            }.shouldBeTrue()

            // AI should have lost (life <= 0)
            ai.life shouldBe 0
        }
    })
