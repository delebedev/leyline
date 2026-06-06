package leyline.mechanics.waterbend

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq

class WaterbendLifecycleTest :
    SessionTest({
        test("Giant Koi pays activated Waterbend through PayCostsReq") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Giant Koi;Coral Merfolk;Grizzly Bears;Sol Ring;Island;Island;Island
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Waterbend Giant Koi",
                validating = true,
            )
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val solRingIid = human.battlefield.iid("Sol Ring")

            val prompt = after { activateAbility("Giant Koi", abilityIndex = 1).shouldBeTrue() }.expectOnePayCostsReq()

            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.PAY_COSTS
                assertWaterbendPaymentActions(
                    prompt,
                    ids = listOf(merfolkIid, bearIid, solRingIid),
                    creatureIds = setOf(merfolkIid, bearIid),
                )
            }

            val resolveSnap = messageSnapshot()
            respondToEffectCost(listOf(merfolkIid, bearIid, solRingIid))
            passUntilResolved(maxPasses = 8)

            val tappedIds =
                messagesSince(resolveSnap)
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.isTapped }
                    .map { it.instanceId }
                    .toSet()

            assertSoftly {
                tappedIds shouldContain merfolkIid
                tappedIds shouldContain bearIid
                tappedIds shouldContain solRingIid
            }
        }

        test("Ruinous Waterbending emits AdditionalCost CastingTimeOption when Waterbend is paid") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ruinous Waterbending
                humanbattlefield=Swamp;Swamp;Swamp;Coral Merfolk;Grizzly Bears;Sol Ring;Manalith
                humanlibrary=Swamp;Swamp;Swamp
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Waterbend Ruinous Waterbending",
                validating = true,
            )

            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val solRingIid = human.battlefield.iid("Sol Ring")
            val manalithIid = human.battlefield.iid("Manalith")

            val cto = after { castSpellByName("Ruinous Waterbending").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            val waterbendOption =
                cto.castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost
                }
            waterbendOption.grpId shouldBe RUINOUS_WATERBEND_ABILITY_GRP_ID

            val payCosts = after { respondToOptionalCost(waterbendOption.ctoId) }.expectOnePayCostsReq()

            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.PAY_COSTS
                payCosts.manaCostList.any { it.count > 0 } shouldBe true
                assertWaterbendPaymentActions(
                    payCosts,
                    ids = listOf(merfolkIid, bearIid, solRingIid, manalithIid),
                    creatureIds = setOf(merfolkIid, bearIid),
                )
            }

            respondToEffectCost(listOf(merfolkIid, bearIid, solRingIid, manalithIid))
            passUntilResolved(maxPasses = 8)

            val additionalCostAnnotations =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .filter { it.detailInt(DetailKeys.TYPE) == CastingTimeOptionType.AdditionalCost.number }

            additionalCostAnnotations shouldHaveSize 1
            additionalCostAnnotations.single().detailInt(DetailKeys.ADDITIONAL_COST_GRP_ID) shouldBe RUINOUS_WATERBEND_ABILITY_GRP_ID
        }
    })

private fun assertWaterbendPaymentActions(
    payCosts: PayCostsReq,
    ids: List<Int>,
    creatureIds: Set<Int>,
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
            mana.abilityGrpId shouldBe 384
            mana.srcInstanceId shouldBe iid
            mana.specsList.map { it.type } shouldContain ManaSpecType.ManaSubstitution
            if (iid in creatureIds) {
                mana.specsList.map { it.type } shouldContain ManaSpecType.FromCreature
            }
        }
    }
}

private const val RUINOUS_WATERBEND_ABILITY_GRP_ID = 192688
