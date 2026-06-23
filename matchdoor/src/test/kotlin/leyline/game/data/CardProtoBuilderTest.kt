package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.SuperType

class CardProtoBuilderTest :
    FunSpec({
        tags(UnitTag)

        test("basic lands receive implicit mana abilities") {
            val repo = InMemoryCardRepository()
            val expected =
                listOf(
                    1 to (SubType.Plains to 1001),
                    2 to (SubType.Island to 1002),
                    3 to (SubType.Swamp to 1003),
                    4 to (SubType.Mountain to 1004),
                    5 to (SubType.Forest to 1005),
                )

            for ((grpId, subtypeAndAbility) in expected) {
                val (subtype, _) = subtypeAndAbility
                repo.registerData(
                    CardData(
                        grpId = grpId,
                        titleId = grpId,
                        power = "",
                        toughness = "",
                        colors = emptyList(),
                        types = listOf(CardType.Land_a80b.number),
                        subtypes = listOf(subtype.number),
                        supertypes = listOf(SuperType.Basic.number),
                        abilityIds = emptyList(),
                        manaCost = emptyList(),
                    ),
                    subtype.name,
                )
            }

            val builder = CardProtoBuilder(repo)

            expected.forEach { (grpId, subtypeAndAbility) ->
                val (_, abilityGrpId) = subtypeAndAbility
                builder
                    .buildObjectInfo(grpId)
                    .build()
                    .uniqueAbilitiesList
                    .map { it.grpId } shouldBe listOf(abilityGrpId)
            }
        }
    })
