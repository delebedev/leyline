package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.config.CostChoicePresentation
import leyline.game.mapping.PromptIds
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
        for (presentation in CostChoicePresentation.entries) {
            for (kick in listOf(true, false)) {
                session(
                    "optional kicker description preserves payment and base cast ($presentation, kick=$kick)",
                    puzzle =
                        """
                            ActivePlayer=Human
                        ActivePhase=Main1
                        HumanLife=20
                        AILife=20
                        humanhand=Shivan Fire;Island
                            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                            humanlibrary=Island;Island;Island
                            aibattlefield=Centaur Courser;Ornithopter
                            ailibrary=Island;Island;Island
                        """.trimIndent(),
                    costChoicePresentation = presentation,
                ) {
                    val target = ai.battlefield.iid("Centaur Courser")
                    val options =
                        after { castSpellByName("Shivan Fire") }
                            .expectOneCastingTimeOptionsReq()
                            .castingTimeOptionReqList
                    val kicker = options.single { it.castingTimeOptionType == CastingTimeOptionType.Kicker }
                    val done = options.single { it.castingTimeOptionType == CastingTimeOptionType.Done }
                    assertSoftly {
                        if (presentation == CostChoicePresentation.ForgeText) {
                            kicker.prompt.parametersList
                                .single()
                                .type shouldBe ParameterType.NonLocalizedString
                            kicker.prompt.parametersList
                                .single()
                                .stringValue shouldContain "Kicker"
                            kicker.prompt.parametersList
                                .single()
                                .stringValue shouldContain "4"
                        } else {
                            kicker.hasPrompt() shouldBe false
                        }
                        done.hasPrompt() shouldBe false
                    }
                    respondToOptionalCost(if (kick) kicker.ctoId else done.ctoId)
                    selectTargets(listOf(target))
                    passUntilResolved(maxPasses = 8)
                    assertSoftly {
                        "Shivan Fire" should beInGraveyardOf(human)
                        human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe if (kick) 5 else 1
                        ai.getZone(ZoneType.Battlefield).cards.map { it.name } shouldBe
                            if (kick) listOf("Ornithopter") else listOf("Centaur Courser", "Ornithopter")
                    }
                    if (kick) {
                        "Centaur Courser" should beInGraveyardOf(ai)
                    } else {
                        ai.battlefield.card("Centaur Courser").damage shouldBe 2
                    }
                }
            }
        }

        for (presentation in CostChoicePresentation.entries) {
            for (pay in listOf(true, false)) {
                session(
                    "offspring title preserves keyword payment and token outcome ($presentation, pay=$pay)",
                    puzzle =
                        """
                        ActivePlayer=Human
                        ActivePhase=Main1
                        HumanLife=20
                        AILife=20
                        humanhand=Coruscation Mage;Island
                        humanbattlefield=Mountain;Mountain;Mountain;Mountain
                        humanlibrary=Island;Island;Island
                        aibattlefield=Ornithopter
                        ailibrary=Island;Island;Island
                        """.trimIndent(),
                    costChoicePresentation = presentation,
                ) {
                    val options =
                        after { castSpellByName("Coruscation Mage") }
                            .expectOneCastingTimeOptionsReq()
                            .castingTimeOptionReqList
                    val offspring = options.single { it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost }
                    val done = options.single { it.castingTimeOptionType == CastingTimeOptionType.Done }
                    if (presentation == CostChoicePresentation.ForgeText) {
                        offspring.prompt.parametersList
                            .single()
                            .stringValue shouldBe "Offspring {2}"
                    } else {
                        offspring.hasPrompt() shouldBe false
                    }
                    done.hasPrompt() shouldBe false
                    respondToOptionalCost(if (pay) offspring.ctoId else done.ctoId)
                    passUntilResolved(maxPasses = 12)
                    assertSoftly {
                        val mages = human.getZone(ZoneType.Battlefield).cards.filter { it.name == "Coruscation Mage" }
                        mages shouldHaveSize if (pay) 2 else 1
                        mages.count { it.isToken && it.netPower == 1 && it.netToughness == 1 } shouldBe if (pay) 1 else 0
                        human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe if (pay) 4 else 2
                        ai.battlefield.card("Ornithopter").damage shouldBe 0
                    }
                }
            }
        }

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

        val lifeState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Bitter Triumph;Grizzly Bears
            humanbattlefield=Swamp;Swamp
            humanlibrary=Island;Island;Island
            aibattlefield=Centaur Courser;Ornithopter
            ailibrary=Island;Island;Island
            """.trimIndent()

        for (presentation in CostChoicePresentation.entries) {
            for (payLife in listOf(true, false)) {
                session(
                    "life or discard choice preserves labels and pays the selected resource ($presentation, life=$payLife)",
                    puzzle = lifeState,
                    costChoicePresentation = presentation,
                ) {
                    val target = ai.battlefield.iid("Centaur Courser")
                    val option =
                        after { castSpellByName("Bitter Triumph") }
                            .expectOneCastingTimeOptionsReq()
                            .castingTimeOptionReqList
                            .single()
                    val selection = option.selectNReq
                    assertSoftly {
                        selection.prompt.parametersList shouldHaveSize 2
                        if (presentation == CostChoicePresentation.Native) {
                            selection.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST
                            selection.prompt.parametersList.map { it.promptId } shouldBe
                                listOf(PromptIds.CHOOSE_OR_COST_PAY_THREE_LIFE, PromptIds.CHOOSE_OR_COST_PAY_DISCARD)
                        } else {
                            selection.prompt.parametersList.map { it.type } shouldBe
                                listOf(ParameterType.NonLocalizedString, ParameterType.NonLocalizedString)
                            selection.prompt.parametersList
                                .first()
                                .stringValue shouldContain "Pay 3 life"
                            selection.prompt.parametersList
                                .last()
                                .stringValue shouldContain "Discard a card"
                        }
                    }
                    respondToAlternateCost(option.ctoId, selection.idsList[if (payLife) 0 else 1])
                    selectTargets(listOf(target))
                    if (!payLife) {
                        val discard = allMessages.last { it.hasSelectNReq() }.selectNReq
                        respondToSelectN(listOf(findInstanceId(discard.idsList, "Grizzly Bears")))
                    }
                    passUntilResolved(maxPasses = 8)
                    assertSoftly {
                        human.life shouldBe if (payLife) 17 else 20
                        "Bitter Triumph" should beInGraveyardOf(human)
                        "Centaur Courser" should beInGraveyardOf(ai)
                        human.getZone(ZoneType.Hand).cards.map { it.name } shouldBe
                            if (payLife) listOf("Grizzly Bears") else emptyList()
                        ai.getZone(ZoneType.Battlefield).cards.map { it.name } shouldBe listOf("Ornithopter")
                    }
                    if (!payLife) "Grizzly Bears" should beInGraveyardOf(human)
                }
            }
        }

        for (presentation in CostChoicePresentation.entries) {
            session(
                "mixed sacrifice and discard costs keep a label for each offered branch in $presentation",
                puzzle = state,
                costChoicePresentation = presentation,
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
                    if (presentation == CostChoicePresentation.Native) {
                        selection.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST
                        selection.prompt.parametersList.map { it.type } shouldBe
                            listOf(ParameterType.PromptId, ParameterType.PromptId)
                        selection.prompt.parametersList.map { it.promptId } shouldBe
                            listOf(PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE_ARTIFACT, PromptIds.CHOOSE_OR_COST_PAY_DISCARD)
                    } else {
                        selection.prompt.parametersList.map { it.type } shouldBe
                            listOf(ParameterType.NonLocalizedString, ParameterType.NonLocalizedString)
                        selection.prompt.parametersList
                            .first()
                            .stringValue shouldContain "Sacrifice an artifact"
                        selection.prompt.parametersList
                            .last()
                            .stringValue shouldContain "Discard a card"
                        selection.prompt.parametersList.map { it.promptId } shouldBe listOf(0, 0)
                    }
                    val emitted = allMessages.last { it.hasCastingTimeOptionsReq() }
                    GREToClientMessage.parseFrom(emitted.toByteArray()) shouldBe emitted
                }
            }

            session(
                "sacrifice choice spends the artifact and draws two cards in $presentation",
                puzzle = state,
                costChoicePresentation = presentation,
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
                "discard choice spends the hand card and draws two cards in $presentation",
                puzzle = state,
                costChoicePresentation = presentation,
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
        }
    })
