package leyline.tooling.simclient

import forge.game.Game
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.cost.Cost
import forge.game.spellability.AbilityActivated
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.codes.SlotKind
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class ForgeAiAbilityActionMatcherTest :
    FunSpec({
        tags(UnitTag)

        test("copied activation matches its definition client row") {
            val card = Card(999, null as Game?)
            card.name = "Same Shape"

            fun ability(): AbilityActivated =
                object : AbilityActivated(card, Cost("1", true), null) {
                    override fun resolve() = Unit
                }.also {
                    it.api = ApiType.Draw
                    card.addSpellAbility(it)
                }
            ability()
            val copiedSecond = ability().copy()
            val registry =
                AbilityRegistry.build(
                    card,
                    CardData(
                        grpId = 9000,
                        titleId = 0,
                        power = "",
                        toughness = "",
                        colors = emptyList(),
                        types = emptyList(),
                        subtypes = emptyList(),
                        supertypes = emptyList(),
                        abilityIds = listOf(9001 to 0, 9002 to 0),
                        abilityKinds = listOf(SlotKind.Activated, SlotKind.Activated),
                        manaCost = emptyList(),
                    ),
                )
            val expected =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Activate_add3)
                    .setInstanceId(77)
                    .setAbilityGrpId(9002)
                    .build()

            chooseActivatedAction(copiedSecond, registry, 77, listOf(expected), emptySet()) shouldBe expected
        }
    })
