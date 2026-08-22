package leyline.mechanics.convoke

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.hasDetail
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import leyline.testkit.persistentAnnotationsOfType
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSelection
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq

class ConvokeLifecycleTest :
    SessionTest({
        session(
            "Convoke creature can satisfy colored mana for action affordability",
            puzzle =
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
        ) {
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

        session(
            "Conclave Tribunal accepts duplicate native Convoke source fields once",
            puzzle =
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
        ) {
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
            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.PAY_COSTS
                initialPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 3
                initialPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.White_afc9) }.count shouldBe 1
                assertConvokePaymentActions(initialPayCosts, listOf(merfolkIid, bearIid))
            }

            val firstPaymentSnap = messageSnapshot()
            val updatedPayCosts = after { respondToConvokeMakePayment(merfolkIid, repeatInManaSelection = true) }.expectOnePayCostsReq()
            val interimConvokeCount =
                messagesSince(firstPaymentSnap).persistentAnnotationsOfType(AnnotationType.AbilityWordActive).filter {
                    it.detailString(DetailKeys.ABILITY_WORD_NAME) == "ConvokeCount"
                }
            assertSoftly {
                updatedPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 2
                updatedPayCosts.paymentActions.actionsList.any { it.instanceId == merfolkIid } shouldBe false
                updatedPayCosts.paymentActions.actionsList.single { it.instanceId == bearIid }
                interimConvokeCount.single().detailInt(DetailKeys.VALUE) shouldBe 1
                interimConvokeCount.single().affectorId shouldBeGreaterThan 0
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
            observe().pendingAction shouldBe false
        }

        session(
            "white creature pays white Convoke pip through native MakePayment",
            puzzle =
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
        ) {
            val lionIid = human.battlefield.iid("Savannah Lions")
            val initialPayCosts = after { castSpellByName("Conclave Tribunal").shouldBeTrue() }.expectOnePayCostsReq()
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
                interimConvokeCount.single().affectorId shouldBeGreaterThan 0
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

        session(
            "selected white source keeps its frozen generic shard when its white peer is omitted",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Conclave Tribunal
                humanbattlefield=Plains;Plains;Plains;Savannah Lions;Savannah Lions
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val initialPayCosts = after { castSpellByName("Conclave Tribunal").shouldBeTrue() }.expectOnePayCostsReq()
            val genericWhiteSource =
                initialPayCosts.paymentActions.actionsList.single { action ->
                    action.manaPaymentOptionsList
                        .single()
                        .manaList
                        .single()
                        .color == ManaColor.Colorless_afc9
                }

            val paymentSnap = messageSnapshot()
            respondToConvokeMakePayment(genericWhiteSource.instanceId)
            respondToConvokePaymentDone()

            val payment =
                annotationsSince(paymentSnap).single {
                    AnnotationType.ManaPaid in it.typeList &&
                        it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                        it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.CONVOKE
                }
            assertSoftly {
                payment.affectorId shouldBe genericWhiteSource.instanceId
                payment.detailInt(DetailKeys.COLOR) shouldBe ManaColor.Colorless_afc9.number
            }
        }

        session(
            "declining all reducers does not mark the spell as convoked",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Conclave Tribunal
                humanbattlefield=Plains;Plains;Plains;Plains;Savannah Lions
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            after { castSpellByName("Conclave Tribunal").shouldBeTrue() }.expectOnePayCostsReq()
            val paymentSnap = messageSnapshot()
            respondToConvokePaymentDone()
            passUntilResolved(maxPasses = 8)

            annotationsSince(paymentSnap)
                .filter { AnnotationType.AbilityWordActive in it.typeList }
                .filter { it.detailString(DetailKeys.ABILITY_WORD_NAME) == "Convoke" } shouldBe emptyList()
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

private fun HeadlessMatch.respondToConvokeMakePayment(
    instanceId: Int,
    repeatInManaSelection: Boolean = false,
) {
    submitAction(
        performAction {
            actionType = ActionType.MakePayment
            this.instanceId = instanceId
            if (repeatInManaSelection) {
                addManaSelections(ManaSelection.newBuilder().setInstanceId(instanceId))
            }
        },
    )
}

private fun HeadlessMatch.respondToConvokePaymentDone() {
    submitAction(performAction { actionType = ActionType.Pass })
}
