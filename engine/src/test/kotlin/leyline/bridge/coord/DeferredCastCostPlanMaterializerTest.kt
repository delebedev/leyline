package leyline.bridge.coord

import forge.game.cost.Cost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.DeferredCastCostPlan.AdditionalCostKind
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.config.CostChoicePresentation
import leyline.game.bundle.CastingTimeOptionsBuilder
import leyline.game.mapping.ActionMapper
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ParameterType

class DeferredCastCostPlanMaterializerTest :
    BoardTest({
        test("alternate choices retain exact engine-thread ability handles behind opaque tokens") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Deadly Precision", human, ZoneType.Hand)
                    repeat(6) { addCard("Swamp", human) }
                    addCard("Grizzly Bears", human)
                }
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Deadly Precision" }
            val castCandidates = candidates.forCard(card).casts
            val cardId = ForgeCardId(card.id)
            val iid = board.bridge.getOrAllocInstanceId(cardId).value
            val grpId = board.bridge.resolveGrpId(card, iid)
            val offer =
                GameActionBridge.ActionOffer(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(iid)
                        .setGrpId(grpId)
                        .build(),
                    PlayerAction.CastSpell(cardId, 0, ability = castCandidates.first()),
                    castCandidates = castCandidates,
                )
            val cardData = board.bridge.cardRepository.findByGrpId(offer.action.grpId)
            val keywordCount =
                board.bridge
                    .abilityRegistryFor(card, cardData)
                    ?.slotLayout
                    ?.keywordCount ?: 0
            var nextToken = 100L

            val result =
                DeferredCastCostPlanMaterializer
                    .materialize(offer, cardData, keywordCount) { nextToken++ }
                    .shouldNotBeNull()
            val choices =
                result.plan.alternate
                    .shouldNotBeNull()
                    .choices

            choices shouldHaveSize offer.castCandidates.size
            val native =
                CastingTimeOptionsBuilder
                    .buildChooseOrCostCastingTimeOptionsReq(
                        iid,
                        grpId,
                        1,
                        choices,
                        CostChoicePresentation.Native,
                    ).first
            val text =
                CastingTimeOptionsBuilder
                    .buildChooseOrCostCastingTimeOptionsReq(
                        iid,
                        grpId,
                        1,
                        choices,
                        CostChoicePresentation.ForgeText,
                    ).first
            val nativeOption = native.castingTimeOptionReqList.single()
            val textOption = text.castingTimeOptionReqList.single()
            assertSoftly {
                nativeOption.selectNReq.prompt.parametersList
                    .map { it.type } shouldBe
                    listOf(ParameterType.PromptId, ParameterType.PromptId)
                textOption.selectNReq.prompt.parametersList
                    .map { it.stringValue } shouldBe choices.map { it.description }
                nativeOption.toBuilder().setSelectNReq(nativeOption.selectNReq.toBuilder().clearPrompt()).build() shouldBe
                    textOption.toBuilder().setSelectNReq(textOption.selectNReq.toBuilder().clearPrompt()).build()
            }
            choices.forEachIndexed { index, choice ->
                val selected =
                    result.childSelections
                        .getValue(choice.runtimeToken)
                        .offer.command as PlayerAction.CastSpell
                selected.ability shouldBeSameInstanceAs offer.castCandidates[index]
            }
        }

        test("printed additional mana survives reductions and later ability changes") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Lightning Axe", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Goblin Electromancer", human)
                    addCard("Grizzly Bears", ai)
                    repeat(6) { addCard("Mountain", human) }
                }
            val card = board.human.hand.card("Lightning Axe")
            val casts = PriorityActionCandidates.query(board.game, board.human).forCard(card).casts
            val iid = board.bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val grpId = board.bridge.resolveGrpId(card, iid)
            val offer =
                GameActionBridge.ActionOffer(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(iid)
                        .setGrpId(grpId)
                        .build(),
                    PlayerAction.CastSpell(ForgeCardId(card.id), 0, ability = casts.first()),
                    castCandidates = casts,
                )
            var token = 1L
            val result =
                DeferredCastCostPlanMaterializer
                    .materialize(offer, board.bridge.cardRepository.findByGrpId(grpId), 0) {
                        token++
                    }.shouldNotBeNull()
            val choices =
                result.plan.alternate
                    .shouldNotBeNull()
                    .choices
            val manaIndex = casts.indexOfFirst { it.payCosts.isOnlyManaCost }
            manaIndex shouldBe 1
            val ability = casts[manaIndex]
            val description = choices[manaIndex].description
            assertSoftly {
                description shouldContain "Additional cost: {5}"
                ability.payCosts.totalMana.cmc shouldBe 6
                ActionMapper.computeEffectiveCost(ability, board.human).shouldNotBeNull().cmc shouldBe 5
            }
            ability.description = "Changed after freezing"
            val text =
                CastingTimeOptionsBuilder
                    .buildChooseOrCostCastingTimeOptionsReq(
                        iid,
                        grpId,
                        1,
                        choices,
                        CostChoicePresentation.ForgeText,
                    ).first
            text.castingTimeOptionReqList
                .single()
                .selectNReq.prompt.parametersList[manaIndex]
                .stringValue shouldBe description
        }

        test("one-card native labels do not erase quantity restrictions or other costs") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Demand Answers", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Ornithopter", human)
                    repeat(2) { addCard("Mountain", human) }
                }
            val card = board.human.hand.card("Demand Answers")
            val casts = PriorityActionCandidates.query(board.game, board.human).forCard(card).casts
            casts shouldHaveSize 2
            val cardId = ForgeCardId(card.id)
            val iid = board.bridge.getOrAllocInstanceId(cardId).value
            val offer =
                GameActionBridge.ActionOffer(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(iid)
                        .build(),
                    PlayerAction.CastSpell(cardId, 0, ability = casts.first()),
                    castCandidates = casts,
                )
            val cases =
                listOf(
                    "PayLife<3>" to AdditionalCostKind.PayLife(3),
                    "PayLife<4>" to AdditionalCostKind.PayLife(4),
                    "PayLife<X>" to AdditionalCostKind.Unsupported,
                    "PayLife<3> Discard<1/Card>" to AdditionalCostKind.Unsupported,
                    "Discard<2/Card>" to AdditionalCostKind.Unsupported,
                    "Discard<1/Card.Black>" to AdditionalCostKind.Unsupported,
                    "Sac<2/Artifact>" to AdditionalCostKind.Sacrifice,
                    "Sac<1/Artifact.YouCtrl>" to AdditionalCostKind.Sacrifice,
                    "Sac<1/Artifact> Discard<1/Card>" to AdditionalCostKind.Sacrifice,
                )
            var token = 1L
            for ((cost, expected) in cases) {
                casts.first().payCosts = Cost("1 R $cost", false)
                val result = DeferredCastCostPlanMaterializer.materialize(offer, null, 0) { token++ }.shouldNotBeNull()
                result.plan.alternate
                    .shouldNotBeNull()
                    .choices
                    .first()
                    .kind shouldBe expected
                if (expected is AdditionalCostKind.PayLife) {
                    val choices =
                        result.plan.alternate
                            .shouldNotBeNull()
                            .choices
                    val native =
                        CastingTimeOptionsBuilder
                            .buildChooseOrCostCastingTimeOptionsReq(
                                iid,
                                0,
                                1,
                                choices,
                                CostChoicePresentation.Native,
                            ).first.castingTimeOptionReqList
                            .single()
                            .selectNReq.prompt
                    native.parametersCount shouldBe if (expected.amount == 3) 2 else 0
                    casts.first().payCosts = Cost("1 R PayLife<5>", false)
                    choices.first().kind shouldBe expected
                }
            }
        }

        test("hybrid plan freezes nested values and preserves the exact offered ability") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Temur Tawnyback", human, ZoneType.Hand)
                    repeat(3) { addCard("Island", human) }
                    repeat(3) { addCard("Mountain", human) }
                    repeat(3) { addCard("Plains", human) }
                }
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Temur Tawnyback" }
            val castCandidates = candidates.forCard(card).casts
            val cardId = ForgeCardId(card.id)
            val iid = board.bridge.getOrAllocInstanceId(cardId).value
            val grpId = board.bridge.resolveGrpId(card, iid)
            val offer =
                GameActionBridge.ActionOffer(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(iid)
                        .setGrpId(grpId)
                        .build(),
                    PlayerAction.CastSpell(cardId, 0, ability = castCandidates.first()),
                    castCandidates = castCandidates,
                )
            val command = offer.command as PlayerAction.CastSpell
            val cardData = board.bridge.cardRepository.findByGrpId(offer.action.grpId)
            val result = DeferredCastCostPlanMaterializer.materialize(offer, cardData, 0) { error("no child token") }.shouldNotBeNull()

            assertSoftly {
                command.ability shouldBeSameInstanceAs offer.castCandidates[command.abilityId!!]
                result.plan.hybrid
                    .shouldNotBeNull()
                    .paymentColors
                    .shouldNotBeEmpty()
                shouldThrow<UnsupportedOperationException> {
                    (result.plan.hybrid.paymentColors as MutableList<ManaColor>).add(ManaColor.Blue_afc9)
                }
                shouldThrow<UnsupportedOperationException> {
                    (
                        result.plan.hybrid.manaCost
                            .first()
                            .colors as MutableList<ManaColor>
                    ).add(ManaColor.Blue_afc9)
                }
            }
        }

        test("optional plan preserves the exact offered ability") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Burst Lightning", human, ZoneType.Hand)
                    repeat(6) { addCard("Mountain", human) }
                }
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Burst Lightning" }
            val castCandidates = candidates.forCard(card).casts
            val cardId = ForgeCardId(card.id)
            val iid = board.bridge.getOrAllocInstanceId(cardId).value
            val grpId = board.bridge.resolveGrpId(card, iid)
            val offer =
                GameActionBridge.ActionOffer(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(iid)
                        .setGrpId(grpId)
                        .build(),
                    PlayerAction.CastSpell(cardId, 0, ability = castCandidates.first()),
                    castCandidates = castCandidates,
                )
            val command = offer.command as PlayerAction.CastSpell
            val cardData = board.bridge.cardRepository.findByGrpId(grpId)
            val result = DeferredCastCostPlanMaterializer.materialize(offer, cardData, 0) { error("no child token") }.shouldNotBeNull()

            command.ability shouldBeSameInstanceAs castCandidates.first()
            result.plan.optional
                .shouldNotBeNull()
                .entries
                .shouldNotBeEmpty()
        }

        test("non-cast offer has no deferred cost plan or runtime handles") {
            val pass = Action.newBuilder().setActionType(ActionType.Pass).build()
            val offer = GameActionBridge.ActionOffer(pass, PlayerAction.PassPriority)

            DeferredCastCostPlanMaterializer.materialize(offer, null, 0) { error("no token") } shouldBe null
        }
    })
