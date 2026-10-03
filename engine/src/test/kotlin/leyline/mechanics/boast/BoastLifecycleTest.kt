package leyline.mechanics.boast

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.codes.DetailKeys
import leyline.testkit.FixturePinned
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

@FixturePinned
class BoastLifecycleTest :
    SessionTest({
        session(
            "Usher of the Fallen Boast gates on attack, exhausts once, and creates a linked token",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Usher of the Fallen;Plains;Plains
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 5,
        ) {
            val usherIid = human.battlefield.iid("Usher of the Fallen")
            latestBoastOffer(usherIid).shouldBeFalse()

            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(usherIid))
            passUntil(maxPasses = 8) { latestBoastOffer(usherIid) }
            latestBoastOffer(usherIid).shouldBeTrue()

            val activationStart = messageSnapshot()
            activateAbility("Usher of the Fallen").shouldBeTrue()
            passUntilResolved(maxPasses = 8)
            val activationMessages = messagesSince(activationStart)

            val tokenCreated =
                activationMessages
                    .annotationsOfType(AnnotationType.TokenCreated)
                    .last()
            val exhausted =
                activationMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityExhausted)
                    .last { usherIid in it.affectedIdsList }
            val tokenIid = tokenCreated.affectedIdsList.single()
            val tokenObject = activationMessages.allGameObjects().firstOrNull { it.instanceId == tokenIid }

            assertSoftly {
                activationMessages.annotationsOfType(AnnotationType.ManaPaid).shouldNotBeEmpty()

                exhausted.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe USHER_BOAST_ABILITY_GRP_ID
                exhausted.detailInt(DetailKeys.UNIQUE_ABILITY_ID) shouldBe BOAST_EXHAUSTED_UNIQUE_ABILITY_ID

                tokenObject shouldNotBe null
                tokenObject!!.grpId shouldBe HUMAN_WARRIOR_TOKEN_GRP_ID
                tokenObject.objectSourceGrpId shouldBe USHER_GRP_ID
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Human Warrior Token" }
                    .shouldNotBeEmpty()

                latestBoastOffer(usherIid).shouldBeFalse()
            }

            passUntilTurn(3, maxPasses = 80)
            val nextTurnAttackStart = messageSnapshot()
            passUntil(maxPasses = 30) {
                turn() >= 3 &&
                    messagesSince(nextTurnAttackStart).any { it.hasDeclareAttackersReq() } &&
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind == PendingActionKind.DECLARE_ATTACKERS
            }.shouldBeTrue()
            declareAttackers(listOf(usherIid))
            passUntil(maxPasses = 8) { latestBoastOffer(usherIid) }
            latestBoastOffer(usherIid).shouldBeTrue()
        }
    })

private fun MatchFlowHarness.latestBoastOffer(usherIid: Int): Boolean {
    val actions = allMessages.lastOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq?.actionsList ?: return false
    return withClue(actions.map { "${it.actionType}:${it.instanceId}:${it.abilityGrpId}" }) {
        actions.any {
            it.actionType == ActionType.Activate_add3 &&
                it.instanceId == usherIid &&
                it.abilityGrpId == USHER_BOAST_ABILITY_GRP_ID
        }
    }
}

private const val USHER_GRP_ID = 75072
private const val USHER_BOAST_ABILITY_GRP_ID = 139868
private const val HUMAN_WARRIOR_TOKEN_GRP_ID = 75364
private const val BOAST_EXHAUSTED_UNIQUE_ABILITY_ID = 374
