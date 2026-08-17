package leyline.behavior.annotations.controllerchanged

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.lastWithPersistentAnnotation
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
    SessionTest({
        session(
            "Act of Treason steals creature, annotations emitted, attack wins",
            """
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
            """.trimIndent(),
            validating = true,
        ) {
            phase() shouldBe "MAIN1"

            val bearsIid = ai.battlefield.iid("Grizzly Bears")

            val steal =
                after {
                    // Cast Act of Treason targeting Grizzly Bears
                    castSpellByName("Act of Treason").shouldBeTrue()
                    selectTargets(listOf(bearsIid))

                    // Pass until Act of Treason resolves and Bears changes controller
                    passUntil(maxPasses = 10) {
                        human.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
                    }.shouldBeTrue()
                }

            // Check annotations from steal
            val gsms = steal.messages.mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
            gsms.size shouldBeGreaterThan 0

            // Find ControllerChanged transient annotation
            val allAnnotations = gsms.flatMap { it.annotationsList }
            val ccTransient =
                allAnnotations
                    .filter { it.typeList.any { t -> t == AnnotationType.ControllerChanged } }
            ccTransient.size shouldBeGreaterThan 0

            // Find LayeredEffectCreated transient
            val lecTransient =
                allAnnotations
                    .filter { it.typeList.any { t -> t == AnnotationType.LayeredEffectCreated } }
            lecTransient.size shouldBeGreaterThan 0

            // Check persistent ControllerChanged+LayeredEffect — address by
            // content, not ordinal `last()`. Trailing post-content echoes
            // carry no persistentAnnotations, so a positional lookup would
            // silently miss the steal.
            val lastGsm =
                checkNotNull(gsms.lastWithPersistentAnnotation(AnnotationType.ControllerChanged)) {
                    "No GSM carries a ControllerChanged persistent annotation"
                }
            val ccPersistent =
                lastGsm.persistentAnnotationsList
                    .filter {
                        it.typeList.any { t -> t == AnnotationType.ControllerChanged } &&
                            it.typeList.any { t -> t == AnnotationType.LayeredEffect }
                    }

            assertSoftly {
                ccPersistent.size shouldBe 1
                ccPersistent[0].detailInt("effect_id") shouldBeGreaterThan 7000
            }

            // Now attack with the stolen creature and win
            advanceToCombat()
            val bearsNewIid = human.battlefield.iid("Grizzly Bears")
            declareAttackers(listOf(bearsNewIid))

            // Pass through combat — AI at 2 life, Bears is 2/2, should be lethal
            passUntil(maxPasses = 20) { isGameOver() }.shouldBeTrue()

            // AI should have lost (life <= 0)
            ai.life shouldBe 0
        }
    })
