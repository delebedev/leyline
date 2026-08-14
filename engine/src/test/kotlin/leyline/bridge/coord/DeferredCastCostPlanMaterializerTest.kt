package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

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
            choices.forEachIndexed { index, choice ->
                val selected =
                    result.childSelections
                        .getValue(choice.runtimeToken)
                        .offer.command as PlayerAction.CastSpell
                selected.ability shouldBeSameInstanceAs offer.castCandidates[index]
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
