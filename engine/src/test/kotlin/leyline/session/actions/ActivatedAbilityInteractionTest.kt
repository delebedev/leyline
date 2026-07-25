package leyline.session.actions

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/**
 * Session-tier activated ability tests — full MatchSession round-trip.
 *
 * Board-level action field tests live in [ActivatedAbilityTest] (BoardTest).
 */
class ActivatedAbilityInteractionTest :
    SessionTest({

        test("Goblin Fireslinger tap-to-ping deals damage to opponent") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=5

                humanbattlefield=Goblin Fireslinger
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
                name = "Tap to Ping",
            )

            assertSoftly {
                phase() shouldBe "MAIN1"
                ai.life shouldBe 5
            }

            // Activate tap ability → wait for SelectTargetsReq before responding
            // (drainSink returns before the engine emits the prompt under load).
            assertSoftly {
                activateAbility("Goblin Fireslinger").shouldBeTrue()
                passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            }
            selectTargets(listOf(OPPONENT_SEAT))

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            val stackAbilityIids =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability }
                    .map { it.instanceId }
            assertSoftly {
                stackAbilityIids shouldContain targetSpec.affectorId
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                passUntil(maxPasses = 10) { ai.life < 5 }.shouldBeTrue()
                ai.life shouldBe 4
                allMessages.deletedPersistentAnnotationIds() shouldContain targetSpec.id
            }
        }

        test("modal activated sacrifice ability asks mode before target and costs") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Goblin Cratermaker;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
                name = "Cratermaker Modal Activate",
            )

            val slice = after { activateAbility("Goblin Cratermaker").shouldBeTrue() }

            slice.expectNoPayCostsReq()
            slice.expectNoSelectTargetsReq()
            val modalReq =
                slice
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()
                    .modalReq
            modalReq.modalOptionsList.map { it.grpId } shouldBe listOf(121501)
            modalReq.excludedOptionsList.map { it.grpId } shouldBe listOf(121502)
        }
    })
