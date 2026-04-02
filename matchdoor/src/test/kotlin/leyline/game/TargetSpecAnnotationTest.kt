package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.conformance.ConformanceTestBase
import leyline.conformance.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * TargetSpec persistent annotation tests — verifies targeting arrows emitted
 * while spells/abilities with targets are on the stack.
 */
class TargetSpecAnnotationTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("targeted spell on stack emits TargetSpec persistent annotation") {
            val (b, game) = base.startWithBoard { g, human, _ ->
                val creature = base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                val spell = base.addCard("Murder", human, ZoneType.Hand)

                val sa = spell.currentState.firstSpellAbility
                sa.activatingPlayer = human
                sa.targets.add(creature)
                g.stack.add(sa)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            val targetAnn = gs.persistentAnnotationsList.firstOrNull { ann ->
                AnnotationType.TargetSpec in ann.typeList
            }
            targetAnn shouldNotBe null
            targetAnn!!.affectedIdsList.size shouldBe 1
            targetAnn.detailInt("index") shouldBe 1
            targetAnn.detailInt("abilityGrpId") shouldBeGreaterThan 0
        }

        test("untargeted spell on stack emits no TargetSpec") {
            val (b, game) = base.startWithBoard { g, human, _ ->
                val spell = base.addCard("Divination", human, ZoneType.Hand)
                val sa = spell.currentState.firstSpellAbility
                sa.activatingPlayer = human
                g.stack.add(sa)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b).gsm

            gs.persistentAnnotationsList.none { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
        }

        test("TargetSpec removed when spell leaves stack") {
            val (b, game) = base.startWithBoard { g, human, _ ->
                val creature = base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                val spell = base.addCard("Murder", human, ZoneType.Hand)
                val sa = spell.currentState.firstSpellAbility
                sa.activatingPlayer = human
                sa.targets.add(creature)
                g.stack.add(sa)
            }

            // First GSM: spell on stack → TargetSpec present
            val gs1 = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)
            gs1.gsm.persistentAnnotationsList.any { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true

            // Simulate resolution: clear the stack
            game.stack.clear()

            // Second GSM: stack empty → TargetSpec removed
            val gs2 = StateMapper.buildFromGame(game, 2, ConformanceTestBase.TEST_MATCH_ID, b)
            gs2.gsm.persistentAnnotationsList.none { ann ->
                AnnotationType.TargetSpec in ann.typeList
            } shouldBe true
        }
    })
