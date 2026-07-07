package leyline.mechanics.convoke

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.hasDetail
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq

class ConvokeLifecycleTest :
    SessionTest({
        test("Convoke creature can satisfy colored mana for action affordability") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Conclave Tribunal
                humanbattlefield=Forest;Forest;Forest;Savannah Lions
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Convoke colored affordability",
                validating = true,
            )

            val tribunalHandIid = human.hand.iid("Conclave Tribunal")
            val actions =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq

            assertSoftly {
                actions.actionsList.any { it.actionType == ActionType.Cast && it.instanceId == tribunalHandIid } shouldBe true
                actions.inactiveActionsList.any { it.actionType == ActionType.Cast && it.instanceId == tribunalHandIid } shouldBe false
            }
        }

        test("Conclave Tribunal accepts native Convoke MakePayment responses") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Conclave Tribunal
                humanbattlefield=Plains;Plains;Plains;Coral Merfolk;Grizzly Bears
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Convoke Conclave Tribunal",
                validating = true,
            )

            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val tribunalHandIid = human.hand.iid("Conclave Tribunal")
            val tribunalCastAction =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == tribunalHandIid }

            tribunalCastAction should haveManaCost(generic = 3, white = 1)

            val initialPayCosts = after { castSpellByName("Conclave Tribunal").shouldBeTrue() }.expectOnePayCostsReq()
            val tribunalForgeId = ForgeCardId(game().stackZone.first().id)
            val tribunalStackIid = harness.bridge.getOrAllocInstanceId(tribunalForgeId).value
            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.PAY_COSTS
                initialPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 3
                initialPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.White_afc9) }.count shouldBe 1
                assertConvokePaymentActions(initialPayCosts, listOf(merfolkIid, bearIid))
            }

            val firstPaymentSnap = messageSnapshot()
            val updatedPayCosts = after { respondToConvokeMakePayment(merfolkIid) }.expectOnePayCostsReq()
            val interimConvokeCount =
                messagesSince(firstPaymentSnap).persistentAnnotationsOfType(AnnotationType.AbilityWordActive).filter {
                    it.detailString(DetailKeys.ABILITY_WORD_NAME) == "ConvokeCount"
                }
            assertSoftly {
                updatedPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 2
                updatedPayCosts.paymentActions.actionsList.any { it.instanceId == merfolkIid } shouldBe false
                updatedPayCosts.paymentActions.actionsList.single { it.instanceId == bearIid }
                interimConvokeCount.single().detailInt(DetailKeys.VALUE) shouldBe 1
                interimConvokeCount.single().affectorId shouldBe tribunalStackIid
            }

            val paymentSnap = messageSnapshot()
            respondToConvokeMakePayment(bearIid)
            respondToConvokePaymentDone()

            val paymentAnnotations = annotationsSince(paymentSnap)
            val convokeManaPaid =
                paymentAnnotations.filter {
                    AnnotationType.ManaPaid in it.typeList &&
                        it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                        it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.CONVOKE
                }
            val convokeCount =
                messagesSince(paymentSnap).persistentAnnotationsOfType(AnnotationType.AbilityWordActive).filter {
                    it.detailString(DetailKeys.ABILITY_WORD_NAME) == "ConvokeCount"
                }
            val convokeTappedIds =
                paymentAnnotations
                    .filter { AnnotationType.TappedUntappedPermanent in it.typeList }
                    .flatMap { it.affectedIdsList }
            val convokeLabel =
                paymentAnnotations.filter {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString(DetailKeys.ABILITY_WORD_NAME) == "Convoke"
                }
            assertSoftly {
                convokeManaPaid shouldHaveSize 2
                convokeManaPaid.map { it.affectorId }.toSet() shouldBe setOf(merfolkIid, bearIid)
                convokeManaPaid.forEach { it.hasDetail(DetailKeys.ID) shouldBe false }
                convokeTappedIds shouldContain merfolkIid
                convokeTappedIds shouldContain bearIid
                convokeCount.map { it.detailInt(DetailKeys.VALUE) } shouldContain 2
                convokeCount.last().detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe KeywordAbilityIds.CONVOKE
                convokeLabel shouldHaveSize 1
                convokeLabel.single().affectorId shouldBe human.battlefield.iid("Conclave Tribunal")
            }

            passUntilResolved(maxPasses = 8)
            human.battlefield.iid("Conclave Tribunal") shouldBeGreaterThan 0
            harness.bridge
                .promptBridge(SeatId(1))
                .journal
                .activeConvokePayments(tribunalForgeId) shouldBe emptyList()
        }

        test("white creature pays white Convoke pip through native MakePayment") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Conclave Tribunal
                humanbattlefield=Plains;Plains;Plains;Savannah Lions
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Convoke Colored Pip",
                validating = true,
            )

            val lionIid = human.battlefield.iid("Savannah Lions")
            val initialPayCosts = after { castSpellByName("Conclave Tribunal").shouldBeTrue() }.expectOnePayCostsReq()
            val tribunalStackIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(game().stackZone.first().id)).value
            assertConvokePaymentActions(initialPayCosts, listOf(lionIid), ManaColor.White_afc9)

            val firstPaymentSnap = messageSnapshot()
            val updatedPayCosts = after { respondToConvokeMakePayment(lionIid) }.expectOnePayCostsReq()
            val interimConvokeCount =
                messagesSince(firstPaymentSnap).persistentAnnotationsOfType(AnnotationType.AbilityWordActive).filter {
                    it.detailString(DetailKeys.ABILITY_WORD_NAME) == "ConvokeCount"
                }
            assertSoftly {
                updatedPayCosts.manaCostList.any { it.colorList == listOf(ManaColor.White_afc9) } shouldBe false
                updatedPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 3
                updatedPayCosts.paymentActions.actionsList.any { it.instanceId == lionIid } shouldBe false
                interimConvokeCount.single().affectorId shouldBe tribunalStackIid
            }

            val paymentSnap = messageSnapshot()
            respondToConvokePaymentDone()
            val convokeManaPaid =
                annotationsSince(paymentSnap).filter {
                    AnnotationType.ManaPaid in it.typeList &&
                        it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                        it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.CONVOKE
                }
            assertSoftly {
                convokeManaPaid.single().affectorId shouldBe lionIid
                convokeManaPaid.single().detailInt(DetailKeys.COLOR) shouldBe ManaColor.White_afc9.number
                convokeManaPaid.single().hasDetail(DetailKeys.ID) shouldBe false
            }

            passUntilResolved(maxPasses = 8)
            human.battlefield.iid("Conclave Tribunal") shouldBeGreaterThan 0
        }
    })

private fun assertConvokePaymentActions(
    payCosts: PayCostsReq,
    ids: List<Int>,
    expectedColor: ManaColor = ManaColor.Colorless_afc9,
) {
    val actions = payCosts.paymentActions.actionsList
    ids.forEach { iid ->
        val action = actions.single { it.instanceId == iid }
        val mana =
            action.manaPaymentOptionsList
                .single()
                .manaList
                .single()
        assertSoftly {
            action.actionType shouldBe ActionType.MakePayment
            action.abilityGrpId shouldBe KeywordAbilityIds.CONVOKE_PAYMENT
            mana.abilityGrpId shouldBe KeywordAbilityIds.CONVOKE_PAYMENT
            mana.srcInstanceId shouldBe iid
            mana.color shouldBe expectedColor
            mana.specsList.map { it.type } shouldContain ManaSpecType.FromCreature
            mana.specsList.map { it.type } shouldContain ManaSpecType.ManaSubstitution
        }
    }
}

private fun SessionTest.respondToConvokeMakePayment(instanceId: Int) {
    harness.session.onPerformAction(
        performAction {
            actionType = ActionType.MakePayment
            this.instanceId = instanceId
        }.toBuilder().setGameStateId(harness.latestPromptGsId()).build(),
    )
    harness.drainSink()
}

private fun SessionTest.respondToConvokePaymentDone() {
    harness.session.onPerformAction(
        performAction { actionType = ActionType.Pass }
            .toBuilder()
            .setGameStateId(harness.latestPromptGsId())
            .build(),
    )
    harness.drainSink()
}
