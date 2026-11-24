package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.cancelActionReq
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel

/**
 * Cancellation policy of SelectTargetsReq by targeting source: spells and activated abilities
 * may abandon the parent action; triggered abilities must target, and an optional trigger is
 * declined through its resolution-time optional action.
 */
class TargetingCancellationPolicyTest :
    SessionTest({

        session(
            "mandatory trigger target prompt offers no cancel and ignores CancelActionReq",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain;Mountain;Mountain;Mountain
                humanhand=Flametongue Kavu
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            castSpellByName("Flametongue Kavu").shouldBeTrue()
            passUntil(maxPasses = 6) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            val prompt = allMessages.last { it.hasSelectTargetsReq() }
            assertSoftly {
                prompt.allowCancel shouldBe AllowCancel.No_a526
                prompt.allowUndo.shouldBeFalse()
            }

            send(submitWithGsId(cancelActionReq()))
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntilResolved()

            "Centaur Courser" should beInGraveyardOf(ai)
        }

        session(
            "optional trigger targets without cancel and is declined through its optional action",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                humanhand=Duplicant
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            castSpellByName("Duplicant").shouldBeTrue()
            passUntil(maxPasses = 6) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            val targetPrompt = allMessages.last { it.hasSelectTargetsReq() }
            val targetPromptCancel = targetPrompt.allowCancel

            holdNextOptionalAction()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntil(maxPasses = 6) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = false)
            passUntilResolved()

            assertSoftly {
                // protocol: SelectTargetsReq.trigger-initial
                targetPromptCancel shouldBe AllowCancel.No_a526
                targetPrompt.allowUndo shouldBe false
                ai.battlefield.card("Centaur Courser").name shouldBe "Centaur Courser"
                human.battlefield.card("Duplicant").name shouldBe "Duplicant"
                game().stack.isEmpty.shouldBeTrue()
            }
        }

        session(
            "activated ability target prompt keeps cancel and cancelling unwinds the activation",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Prodigal Pyromancer
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            activateAbility("Prodigal Pyromancer").shouldBeTrue()
            passUntil(maxPasses = 5) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            val prompt = allMessages.last { it.hasSelectTargetsReq() }
            assertSoftly {
                // protocol: SelectTargetsReq.spell-cancel
                prompt.allowCancel shouldBe AllowCancel.Abort
                prompt.allowUndo.shouldBeTrue()
            }

            cancelAction()

            assertSoftly {
                bridge.cutCoordinator.targeting.current() shouldBe null
                game().stack.isEmpty.shouldBeTrue()
                human.battlefield
                    .card("Prodigal Pyromancer")
                    .isTapped
                    .shouldBeFalse()
                ai.life shouldBe 20
            }
        }
    })
