package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Verifies that attacking creatures show tapped state on the wire after SubmitAttackers.
 *
 * Real server sends BOTH:
 * 1. `isTapped=true` + `attackState=Attacking` on the creature's GameObjectInfo in the diff
 * 2. `TappedUntappedPermanent` annotation (type 4) with `details.tapped=1` (int32)
 *
 * Regression test for leyline-o2q: human-seat attackers were missing tap + attack state.
 */
class AttackerTapStateTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("attacker creature is tapped with attackState in post-submit GSM diff") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Turn 1: play Mountain, cast Raging Goblin (haste)
            h.playLand().shouldBeTrue()
            h.resolveSpell("Raging Goblin").shouldBeTrue()

            h.turn() shouldBe 1
            h.isAiTurn().shouldBeFalse()

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val attackerIid = creatures.first().first

            // Advance to combat — triggers DeclareAttackersReq
            h.passPriority()
            h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Toggle creature ON, then submit — captures the post-submit messages
            h.toggleAttackers(listOf(attackerIid))
            val snap = h.messageSnapshot()
            h.submitAttackers()
            val postSubmit = h.messagesSince(snap)

            // Find post-submit GSM diffs
            val postSubmitGsms = postSubmit
                .filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }
            postSubmitGsms.shouldNotBeEmpty()

            // Find the creature with attackState=Attacking in any post-submit diff
            val attackerObj = postSubmitGsms
                .flatMap { it.gameObjectsList }
                .firstOrNull { it.instanceId == attackerIid && it.attackState == AttackState.Attacking }

            attackerObj.shouldNotBeNull()
            attackerObj.isTapped.shouldBeTrue()
        }

        test("TappedUntappedPermanent annotation emitted for attacker") {
            val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
            harness = h
            h.connectAndKeep()

            h.installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            // Turn 1: play Mountain, cast Raging Goblin (haste)
            h.playLand().shouldBeTrue()
            h.resolveSpell("Raging Goblin").shouldBeTrue()

            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val attackerIid = creatures.first().first

            // Advance to combat, declare attack
            h.passPriority()
            val snap = h.messageSnapshot()
            h.declareAttackers(listOf(attackerIid))
            val postAttack = h.messagesSince(snap)

            // Collect all annotations from post-attack GSMs
            val allAnnotations = postAttack
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }

            // Find TappedUntappedPermanent for our attacker
            val tapAnnotation = allAnnotations.firstOrNull { ann ->
                ann.typeList.any { it == AnnotationType.TappedUntappedPermanent } &&
                    attackerIid in ann.affectedIdsList
            }

            tapAnnotation.shouldNotBeNull()

            // Conformance: tapped detail should be 1 (int32, matching real server)
            tapAnnotation.detailInt("tapped") shouldBe 1
        }
    })
