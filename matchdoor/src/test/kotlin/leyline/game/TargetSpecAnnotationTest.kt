package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.detailInt
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * TargetSpec persistent annotation tests — verifies targeting arrows emitted
 * via the bridge-side pending target store.
 *
 * Targets are captured during selectTargetsInteractively and stored on
 * InteractivePromptBridge. buildTargetSpecAnnotations reads from the store,
 * not from the live stack (spell may have already resolved).
 */
class TargetSpecAnnotationTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("pending target spec emits TargetSpec persistent annotation") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Murder", human, ZoneType.Hand)
            }

            val creature = b.getPlayer(SeatId(1))!!
                .getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val spell = b.getPlayer(SeatId(1))!!
                .getZone(ZoneType.Hand).cards.first { it.name == "Murder" }

            // Simulate what selectTargetsInteractively does: add pending target
            b.seat(1).prompt.addPendingTargetSpec(
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = spell.id,
                    spellName = spell.name,
                    targetForgeCardId = creature.id,
                    index = 1,
                ),
            )

            val snapTarget1 = GsmSnapshot.capture(game, b, ConformanceTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snapTarget1, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val targetAnn = gs.persistentAnnotationsList.firstOrNull { ann ->
                AnnotationType.TargetSpec in ann.typeList
            }
            assertSoftly {
                targetAnn shouldNotBe null
                targetAnn!!.affectedIdsList.size shouldBe 1
                targetAnn.detailInt("index") shouldBe 1
                targetAnn.detailInt("abilityGrpId") shouldBeGreaterThan 0
            }
        }

        test("no pending targets emits no TargetSpec") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Divination", human, ZoneType.Hand)
            }

            val snapTarget2 = GsmSnapshot.capture(game, b, ConformanceTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snapTarget2, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            gs.persistentAnnotationsList.none { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
        }

        test("TargetSpec removed when pending list is empty on next build") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                base.addCard("Murder", human, ZoneType.Hand)
            }

            val creature = b.getPlayer(SeatId(1))!!
                .getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val spell = b.getPlayer(SeatId(1))!!
                .getZone(ZoneType.Hand).cards.first { it.name == "Murder" }

            b.seat(1).prompt.addPendingTargetSpec(
                InteractivePromptBridge.PendingTarget(
                    spellForgeCardId = spell.id,
                    spellName = spell.name,
                    targetForgeCardId = creature.id,
                    index = 1,
                ),
            )

            // First GSM: pending target → TargetSpec present
            val snapTs1 = GsmSnapshot.capture(game, b, ConformanceTestBase.TEST_MATCH_ID, 1)
            val gs1 = StateMapper.buildFromSnapshot(snapTs1, 1, ConformanceTestBase.TEST_MATCH_ID, b)
            gs1.gsm.persistentAnnotationsList.any { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true

            // Second GSM: pending drained, no new targets → TargetSpec removed
            val snapTs2 = GsmSnapshot.capture(game, b, ConformanceTestBase.TEST_MATCH_ID, 2)
            val gs2 = StateMapper.buildFromSnapshot(snapTs2, 2, ConformanceTestBase.TEST_MATCH_ID, b)
            gs2.gsm.persistentAnnotationsList.none { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
        }
    })
