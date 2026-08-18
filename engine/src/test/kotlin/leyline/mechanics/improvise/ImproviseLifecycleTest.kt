package leyline.mechanics.improvise

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.hasDetail
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

class ImproviseLifecycleTest :
    SessionTest({
        session(
            "Ironheart pays Improvise through PayCostsReq",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ironheart, Clever Champion
                humanbattlefield=Island;Ornithopter;Short Sword;Witching Well;Gleaming Barrier
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val artifactIids =
                listOf(
                    human.battlefield.iid("Ornithopter"),
                    human.battlefield.iid("Short Sword"),
                    human.battlefield.iid("Witching Well"),
                    human.battlefield.iid("Gleaming Barrier"),
                )
            val ironheartIid = human.hand.iid("Ironheart, Clever Champion")
            val castAction =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == ironheartIid }

            castAction should haveManaCost(generic = 4, blue = 1)

            val initialPayCosts = after { castSpellByName("Ironheart, Clever Champion").shouldBeTrue() }.expectOnePayCostsReq()
            initialPayCosts.paymentActions.actionsList
                .map { it.instanceId }
                .toSet() shouldBe artifactIids.toSet()
            initialPayCosts.paymentActions.actionsList.forEach { action ->
                val mana =
                    action.manaPaymentOptionsList
                        .single()
                        .manaList
                        .single()
                assertSoftly {
                    mana.color shouldBe ManaColor.Colorless_afc9
                    mana.abilityGrpId shouldBe KeywordAbilityIds.IMPROVISE
                    mana.specsList.map { it.type } shouldContainAll listOf(ManaSpecType.ManaSubstitution)
                }
            }

            val paymentSnap = messageSnapshot()
            artifactIids.forEach { respondToMakePayment(it) }
            respondToPaymentDone()

            val annotations = annotationsSince(paymentSnap)
            val tappedIds = annotations.filter { AnnotationType.TappedUntappedPermanent in it.typeList }.flatMap { it.affectedIdsList }
            val paidByImprovise =
                annotations.filter {
                    AnnotationType.ManaPaid in it.typeList &&
                        it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                        it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.IMPROVISE
                }

            assertSoftly {
                tappedIds shouldContainAll artifactIids
                paidByImprovise.map { it.affectorId }.toSet() shouldBe artifactIids.toSet()
                messagesSince(paymentSnap).persistentAnnotationsOfType(AnnotationType.CastingTimeOption).shouldBeEmpty()
            }
        }
    })

private fun MatchFlowHarness.respondToMakePayment(instanceId: Int) {
    session.onPerformAction(
        submitWithGsId(
            performAction {
                actionType = ActionType.MakePayment
                this.instanceId = instanceId
            }.toBuilder().setGameStateId(latestPromptGsId()).build(),
        ),
    )
    drainSink()
}

private fun MatchFlowHarness.respondToPaymentDone() {
    session.onPerformAction(
        submitWithGsId(
            performAction { actionType = ActionType.Pass }
                .toBuilder()
                .setGameStateId(latestPromptGsId())
                .build(),
        ),
    )
    drainSink()
}
