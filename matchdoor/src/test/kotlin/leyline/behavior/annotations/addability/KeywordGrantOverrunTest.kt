package leyline.behavior.annotations.addability

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Integration test for keyword grant via Overrun (grpId 93943).
 *
 * Overrun: all creatures you control get +3/+3 and trample until end of turn.
 * Tests the full keyword grant pipeline:
 *   Forge event → GameEventCollector → EffectTracker → AddAbility pAnn + uniqueAbilities on gameObject.
 */
class KeywordGrantOverrunTest :
    SessionTest({

        test("Overrun: creatures get AddAbility pAnn with Trample grpId") {
            startPuzzleFile("puzzles/keyword-grant-overrun.pzl", validating = true)

            castSpellByName("Overrun").shouldBeTrue()
            // Pass priority to let Overrun resolve
            passPriority()

            // Find AddAbility persistent annotation
            val addAbility =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .firstOrNull { AnnotationType.AddAbility_af5a in it.typeList }
            assertSoftly {
                addAbility.shouldNotBeNull()
                // grpId 14 = Trample
                addAbility.detailUint("grpid") shouldBe 14
                // Both Grizzly Bears affected
                addAbility.affectedIdsList.size shouldBe 2
            }
        }

        test("Overrun: creature gameObjects have Trample in uniqueAbilities") {
            startPuzzleFile("puzzles/keyword-grant-overrun.pzl", validating = true)

            val bears =
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Grizzly Bears" }
            bears.size shouldBe 2

            castSpellByName("Overrun").shouldBeTrue()
            // Pass priority to let Overrun resolve
            passPriority()

            val bearIids = bears.map { human.battlefield.iid(it) }.toSet()
            val bearObjects = bearIids.mapNotNull { harness.accumulator.objects[it] }
            bearObjects.shouldNotBeEmpty()

            for (obj in bearObjects) {
                val trampleAbility = obj.uniqueAbilitiesList.firstOrNull { it.grpId == 14 }
                trampleAbility.shouldNotBeNull()
            }
        }
    })
