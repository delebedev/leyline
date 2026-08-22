package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement

class PromptCastOfferMatcherTest :
    FunSpec({
        tags(UnitTag)

        test("a unique source offer preserves its dedicated cast action") {
            val adventure = action(ActionType.CastAdventure, instanceId = 10, cost = mana(ManaColor.Red_afc9, 1, 4))

            choosePromptCastOffer(
                actions = listOf(adventure),
                sourceInstanceId = 10,
                sourceGrpId = 100,
                displayedManaCost = mana(ManaColor.Red_afc9, 1, 4),
            ) shouldBe adventure
        }

        test("displayed mana selects an Omen offer without Omen policy logic") {
            val main = action(ActionType.Cast, instanceId = 10, cost = mana(ManaColor.White_afc9, 1, 4))
            val omen = action(ActionType.CastOmen, instanceId = 10, cost = mana(ManaColor.White_afc9, 1, 1))

            choosePromptCastOffer(
                actions = listOf(main, omen),
                sourceInstanceId = 10,
                sourceGrpId = 100,
                displayedManaCost = mana(ManaColor.White_afc9, 1, 1),
            ) shouldBe omen
        }

        test("alternative identity disambiguates equal-cost generic cast offers") {
            val base = action(ActionType.Cast, instanceId = 10, cost = mana(ManaColor.Blue_afc9, 1, 2))
            val alternative =
                action(
                    ActionType.Cast,
                    instanceId = 10,
                    alternativeGrpId = 77,
                    cost = mana(ManaColor.Blue_afc9, 1, 2),
                )

            choosePromptCastOffer(
                actions = listOf(base, alternative),
                sourceInstanceId = 10,
                sourceGrpId = 100,
                displayedManaCost = mana(ManaColor.Blue_afc9, 1, 2),
                expectedAlternativeGrpId = 77,
            ) shouldBe alternative
        }

        test("base-face preference disambiguates an equal-cost base cast") {
            val base = action(ActionType.Cast, instanceId = 10, cost = mana(ManaColor.White_afc9, 1, 1))
            val option = action(ActionType.CastOmen, instanceId = 10, cost = mana(ManaColor.White_afc9, 1, 1))

            choosePromptCastOffer(
                actions = listOf(base, option),
                sourceInstanceId = 10,
                sourceGrpId = 100,
                displayedManaCost = mana(ManaColor.White_afc9, 1, 1),
                preferBaseCast = true,
            ) shouldBe base
        }

        test("an equal-cost option ambiguity fails closed") {
            choosePromptCastOffer(
                actions =
                    listOf(
                        action(ActionType.CastAdventure, instanceId = 10, cost = mana(ManaColor.Red_afc9, 1, 2)),
                        action(ActionType.CastOmen, instanceId = 10, cost = mana(ManaColor.Red_afc9, 1, 2)),
                    ),
                sourceInstanceId = 10,
                sourceGrpId = 100,
                displayedManaCost = mana(ManaColor.Red_afc9, 1, 2),
            ).shouldBeNull()
        }
    })

private fun action(
    type: ActionType,
    instanceId: Int,
    alternativeGrpId: Int = 0,
    cost: List<ManaRequirement>,
): Action =
    Action
        .newBuilder()
        .setActionType(type)
        .setInstanceId(instanceId)
        .setGrpId(100)
        .setAlternativeGrpId(alternativeGrpId)
        .addAllManaCost(cost)
        .build()

private fun mana(
    color: ManaColor,
    colored: Int,
    generic: Int,
): List<ManaRequirement> =
    listOfNotNull(
        colored.takeIf { it > 0 }?.let {
            ManaRequirement
                .newBuilder()
                .addColor(color)
                .setCount(it)
                .build()
        },
        generic.takeIf { it > 0 }?.let {
            ManaRequirement
                .newBuilder()
                .addColor(ManaColor.Generic)
                .setCount(it)
                .build()
        },
    )
