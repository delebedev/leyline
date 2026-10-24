package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.ActivatedAbilityOccurrence
import leyline.game.snapshot.CardSnapshot
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.UniqueAbilityInfo

class ObjectMapperActivationOccurrenceTest :
    FunSpec({
        tags(UnitTag)

        val base = ActivatedAbilityOccurrence(0, 12, 300, 50, false)
        val alternative = base.copy(abilityIndex = 1, isAlternative = true)
        val source =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(101)
                .setGrpId(200)
                .addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(50).setGrpId(300))
                .addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(88).setGrpId(400))
                .addAbilityOriginalCardGrpIds(200)
                .addAbilityOriginalCardGrpIds(201)
                .build()
        val card =
            CardSnapshot(
                ForgeCardId(1),
                "Authored source",
                200,
                SeatId(1),
                SeatId(1),
                activationOccurrences = mapOf(SeatId(1) to listOf(base, alternative, alternative.copy(abilityIndex = 2))),
            )

        test("alternative occurrences share catalog definitions without colliding with complete projected rows") {
            val result = ObjectMapper.planActivationOccurrences(listOf(source), mapOf(101 to card))
            val plan = result.occurrences.getValue(ForgeCardId(1)).getValue(SeatId(1))
            assertSoftly {
                plan.map { it.uniqueAbilityId } shouldBe listOf(50, 89, 90)
                plan.map { it.abilityGrpId } shouldBe listOf(300, 300, 300)
                result.objects
                    .single()
                    .uniqueAbilitiesList
                    .map { it.id to it.grpId } shouldBe
                    listOf(50 to 300, 88 to 400, 89 to 300, 90 to 300)
                result.objects.single().abilityOriginalCardGrpIdsList shouldBe listOf(200, 201, 200, 200)
            }
        }

        test("unchanged projection is deterministic and a base-only source keeps its rows") {
            val first = ObjectMapper.planActivationOccurrences(listOf(source), mapOf(101 to card))
            val repeat = ObjectMapper.planActivationOccurrences(listOf(source), mapOf(101 to card))
            val plain = ObjectMapper.planActivationOccurrences(listOf(source), mapOf(101 to card.copy(activationOccurrences = emptyMap())))
            assertSoftly {
                repeat shouldBe first
                plain.objects shouldBe listOf(source)
                plain.occurrences shouldBe emptyMap()
                source.uniqueAbilitiesCount shouldBe 2
            }
        }

        test("late granted rows reserve their actual ids before alternatives are assigned") {
            val granted =
                source
                    .toBuilder()
                    .addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(89).setGrpId(500))
                    .addAbilityOriginalCardGrpIds(202)
                    .build()
            val result = ObjectMapper.planActivationOccurrences(listOf(granted), mapOf(101 to card))
            assertSoftly {
                result.occurrences
                    .getValue(card.forgeCardId)
                    .getValue(SeatId(1))
                    .map { it.uniqueAbilityId } shouldBe listOf(50, 90, 91)
                result.objects
                    .single()
                    .uniqueAbilitiesList
                    .take(3) shouldBe granted.uniqueAbilitiesList
                result.objects.single().abilityOriginalCardGrpIdsList shouldBe listOf(200, 201, 202, 200, 200)
            }
        }

        test("seat occurrence domains are deterministic and do not reuse a sibling seat alias") {
            val multiSeat =
                card.copy(
                    activationOccurrences =
                        linkedMapOf(
                            SeatId(2) to listOf(base, alternative),
                            SeatId(1) to listOf(base, alternative),
                        ),
                )
            val result = ObjectMapper.planActivationOccurrences(listOf(source), mapOf(101 to multiSeat))
            val plan = result.occurrences.getValue(card.forgeCardId)
            assertSoftly {
                plan.getValue(SeatId(1)).map { it.uniqueAbilityId } shouldBe listOf(50, 89)
                plan.getValue(SeatId(2)).map { it.uniqueAbilityId } shouldBe listOf(50, 90)
                ObjectMapper.planActivationOccurrences(
                    listOf(source),
                    mapOf(
                        101 to
                            multiSeat.copy(
                                activationOccurrences =
                                    multiSeat.activationOccurrences.entries
                                        .reversed()
                                        .associate { it.toPair() },
                            ),
                    ),
                ) shouldBe result
            }
        }

        test("face-down sources receive no alternative aliases or occurrence plan") {
            val hidden = source.toBuilder().setIsFacedown(true).build()
            val result = ObjectMapper.planActivationOccurrences(listOf(hidden), mapOf(101 to card))
            result.objects shouldBe listOf(hidden)
            result.occurrences shouldBe emptyMap()
        }
    })
