package leyline.session.combat

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.*
import leyline.testkit.after

/**
 * Verifies that attacking creatures appear with tapped state in the post-submit
 * diff GSM.
 *
 * Client expects BOTH:
 * 1. `isTapped=true` + `attackState=Attacking` on the creature's GameObjectInfo in the diff
 * 2. `TappedUntappedPermanent` annotation (type 4) with `details.tapped=1` (int32)
 *
 * Regression test for leyline-o2q: human-seat attackers were missing tap + attack state.
 */
class AttackerTapStateTest :
    SessionTest({

        session("attacker creature is tapped with attackState in post-submit GSM diff", deckList = COMBAT_DECK, validating = true) {
            installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            assertSoftly {
                playLand("Mountain").shouldBeTrue()
                resolveSpell("Raging Goblin").shouldBeTrue()
            }

            assertSoftly {
                turn() shouldBe 1
                isAiTurn().shouldBeFalse()
            }

            val creatures = humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val attackerIid = creatures.first().first

            // Advance to combat — triggers DeclareAttackersReq
            passPriority()
            allMessages.lastOrNull { it.hasDeclareAttackersReq() }.shouldNotBeNull()

            // Toggle creature ON, then submit — collect the post-submit messages
            toggleAttackers(listOf(attackerIid))
            val postSubmit = after { submitAttackers() }.messages

            postSubmit.gameStateMessages().shouldNotBeEmpty()

            // Find the creature with attackState=Attacking in any post-submit diff
            val attackerObj =
                postSubmit
                    .allGameObjects()
                    .firstOrNull { it.instanceId == attackerIid && it.attackState == AttackState.Attacking }

            assertSoftly {
                attackerObj.shouldNotBeNull()
                attackerObj.isTapped.shouldBeTrue()
            }
        }

        session("TappedUntappedPermanent annotation emitted for attacker", deckList = COMBAT_DECK, validating = true) {
            installScriptedAi(
                listOf(
                    ScriptedAction.PlayLand("Mountain"),
                    ScriptedAction.DeclareNoAttackers,
                    ScriptedAction.PassPriority,
                ),
            )

            assertSoftly {
                playLand("Mountain").shouldBeTrue()
                resolveSpell("Raging Goblin").shouldBeTrue()
            }

            val creatures = humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val attackerIid = creatures.first().first

            // Advance to combat, declare attack
            passPriority()
            val postAttack = after { declareAttackers(listOf(attackerIid)) }.messages

            // Find TappedUntappedPermanent for our attacker
            val tapAnnotation =
                postAttack
                    .annotationsOfType(AnnotationType.TappedUntappedPermanent)
                    .firstOrNull { attackerIid in it.affectedIdsList }

            assertSoftly {
                tapAnnotation.shouldNotBeNull()
                tapAnnotation.detailInt("tapped") shouldBe 1
            }
        }
    })
