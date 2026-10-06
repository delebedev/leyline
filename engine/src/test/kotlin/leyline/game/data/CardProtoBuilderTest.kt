package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.FaceDownKind
import leyline.game.state.EffectTracker
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.SuperType

class CardProtoBuilderTest :
    FunSpec({
        tags(UnitTag)

        test("face-down projections retain independently granted Ward") {
            val builder = CardProtoBuilder(InMemoryCardRepository())
            for (kind in FaceDownKind.entries) {
                val snapshot =
                    CardSnapshot(ForgeCardId(1), "Hidden creature", 1, SeatId(1), SeatId(1), isOnBattlefield = true, faceDownKind = kind)
                val projected =
                    ObjectMapper.buildFromSnapshot(
                        snapshot,
                        instanceId = 42,
                        zoneId = ZoneIds.BATTLEFIELD,
                        ownerSeatId = 1,
                        cardProto = builder,
                        keywordSnapshot =
                            mapOf(
                                42 to listOf(EffectTracker.KeywordEntry(1, 1, "Ward", abilityGrpId = KeywordAbilityIds.WARD_TWO)),
                            ),
                    )
                val intrinsicCount = if (kind == FaceDownKind.ManifestDread) 0 else 1
                projected.uniqueAbilitiesList.map { it.grpId } shouldBe List(intrinsicCount + 1) { KeywordAbilityIds.WARD_TWO }
                projected.uniqueAbilitiesList.map { it.id } shouldBe (50..50 + intrinsicCount).toList()
            }
        }

        test("both object projections preserve every subtype number and omit unknown values") {
            val repo = InMemoryCardRepository()
            val subtypes = SubType.values().filter { it != SubType.UNRECOGNIZED }.map { it.number }
            repo.registerData(
                CardData(
                    grpId = 1,
                    titleId = 1,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = listOf(Int.MIN_VALUE) + subtypes + listOf(-1, Int.MAX_VALUE),
                    supertypes = emptyList(),
                    abilityIds = emptyList(),
                    manaCost = emptyList(),
                ),
                "Subtype projection",
            )
            val builder = CardProtoBuilder(repo)
            builder.buildObjectInfo(1).build().subtypesValueList shouldBe subtypes
            builder.buildObjectInfo(1, GameObjectInfo.getDefaultInstance()).subtypesValueList shouldBe subtypes
        }

        test("projection failure adds numeric context and preserves the original cause") {
            val cause = ArithmeticException("failed conversion")
            val failure =
                shouldThrow<IllegalStateException> {
                    projectCardValue(42, "subtypes", 7) { throw cause }
                }
            assertSoftly {
                failure.message shouldBe "Card projection failed: grpId=42 field=subtypes value=7"
                failure.cause shouldBe cause
                projectCardValue(42, "types", 1) { 9 } shouldBe 9
            }
        }

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
