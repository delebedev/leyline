package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
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
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Overrun: creatures get AddAbility pAnn with Trample grpId") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/keyword-grant-overrun.pzl")

            h.castSpellByName("Overrun").shouldBeTrue()
            // Pass priority to let Overrun resolve
            h.passPriority()

            // Find AddAbility persistent annotation
            val addAbility = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .firstOrNull { AnnotationType.AddAbility_af5a in it.typeList }
            addAbility.shouldNotBeNull()

            assertSoftly {
                // grpId 14 = Trample
                addAbility.detailUint("grpid") shouldBe 14
                // Both Grizzly Bears affected
                addAbility.affectedIdsList.size shouldBe 2
            }
        }

        test("Overrun: creature gameObjects have Trample in uniqueAbilities") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/keyword-grant-overrun.pzl")

            val human = h.game().registeredPlayers.first()
            val bears = human.getZone(ForgeZoneType.Battlefield).cards
                .filter { it.name == "Grizzly Bears" }
            bears.size shouldBe 2

            h.castSpellByName("Overrun").shouldBeTrue()
            // Pass priority to let Overrun resolve
            h.passPriority()

            // Get the latest GSM with game objects
            val lastGsm = h.allMessages
                .filter { it.hasGameStateMessage() }
                .last { it.gameStateMessage.gameObjectsCount > 0 }
                .gameStateMessage

            val bearIids = bears.map { h.bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }.toSet()
            val bearObjects = lastGsm.gameObjectsList.filter { it.instanceId in bearIids }
            bearObjects.shouldNotBeEmpty()

            for (obj in bearObjects) {
                val trampleAbility = obj.uniqueAbilitiesList.firstOrNull { it.grpId == 14 }
                trampleAbility.shouldNotBeNull()
            }
        }
    })
