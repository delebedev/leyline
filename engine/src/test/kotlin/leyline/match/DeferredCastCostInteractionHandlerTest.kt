package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.config.CostChoicePresentation
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.beInGraveyardOf
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext

class DeferredCastCostInteractionHandlerTest :
    SessionTest({
        val state =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Demand Answers;Grizzly Bears
            humanbattlefield=Mountain;Mountain;Ornithopter
            humanlibrary=Island;Island;Island;Island
            ailibrary=Island;Island;Island
            """.trimIndent()

        session(
            "mixed sacrifice and discard costs keep a label for each offered branch",
            puzzle = state,
            costChoicePresentation = CostChoicePresentation.ForgeText,
        ) {
            val option =
                after { castSpellByName("Demand Answers") }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()
            val selection = option.selectNReq

            assertSoftly {
                option.castingTimeOptionType shouldBe CastingTimeOptionType.ChooseOrCost
                option.isRequired shouldBe true
                selection.idType shouldBe IdType.PromptParameterIndex
                selection.idsList shouldBe listOf(1, 2)
                selection.prompt.parametersList shouldHaveSize selection.idsCount
                selection.prompt.parametersList.map { it.type } shouldBe
                    listOf(ParameterType.NonLocalizedString, ParameterType.NonLocalizedString)
                selection.prompt.parametersList
                    .first()
                    .stringValue shouldContain "Sacrifice an artifact"
                selection.prompt.parametersList
                    .last()
                    .stringValue shouldContain "Discard a card"
                selection.prompt.parametersList.map { it.promptId } shouldBe listOf(0, 0)
                val emitted = allMessages.last { it.hasCastingTimeOptionsReq() }
                GREToClientMessage.parseFrom(emitted.toByteArray()) shouldBe emitted
            }
        }

        session(
            "sacrifice choice spends the artifact and draws two cards",
            puzzle = state,
            costChoicePresentation = CostChoicePresentation.ForgeText,
        ) {
            val artifactId = human.battlefield.iid("Ornithopter")
            val option =
                after { castSpellByName("Demand Answers") }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()
            val cost =
                after { respondToAlternateCost(option.ctoId, option.selectNReq.idsList.first()) }
                    .expectOnePayCostsReq()
            cost.effectCostReq.costSelection.idsList shouldContain artifactId
            respondToEffectCost(listOf(artifactId))
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                "Demand Answers" should beInGraveyardOf(human)
                "Ornithopter" should beInGraveyardOf(human)
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .map { it.name }
                    .sorted() shouldBe
                    listOf("Grizzly Bears", "Island", "Island")
                human.getZone(ZoneType.Library).cards.size shouldBe 2
            }
        }

        session(
            "discard choice spends the hand card and draws two cards",
            puzzle = state,
            costChoicePresentation = CostChoicePresentation.ForgeText,
        ) {
            val option =
                after { castSpellByName("Demand Answers") }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()
            val cost =
                after { respondToAlternateCost(option.ctoId, option.selectNReq.idsList.last()) }
                    .expectOneSelectNReq()
            assertSoftly {
                cost.context shouldBe SelectionContext.Discard_a163
                cost.idsList shouldHaveSize 1
                findInstanceId(cost.idsList, "Grizzly Bears") shouldBe cost.idsList.single()
            }
            respondToSelectN(cost.idsList)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                "Demand Answers" should beInGraveyardOf(human)
                "Grizzly Bears" should beInGraveyardOf(human)
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .map { it.name }
                    .sorted() shouldBe
                    listOf("Mountain", "Mountain", "Ornithopter")
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldBe listOf("Island", "Island")
                human.getZone(ZoneType.Library).cards.size shouldBe 2
            }
        }
    })
