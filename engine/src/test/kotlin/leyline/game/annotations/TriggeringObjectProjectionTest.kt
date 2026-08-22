package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import leyline.game.data.AbilityInfo
import leyline.game.mapping.ProjectionCardReferences

class TriggeringObjectProjectionTest :
    FunSpec({
        tags(UnitTag)

        val repository = InMemoryCardRepository()
        val references = ProjectionCardReferences(repository)

        beforeSpec {
            repository.registerAbilityInfo(
                170349,
                AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2),
            )
            repository.registerAbilityInfo(
                170350,
                AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2, subCategory = 27),
            )
        }

        test("ordinary ETB trigger emits TriggeringObject") {
            TriggeringObjectProjection
                .shouldEmit(
                    abilityGrpId = 170349,
                    isActivatedAbility = false,
                    voidTrigger = false,
                    cardReferences = references,
                ) shouldBe true
        }

        test("activated abilities do not emit TriggeringObject") {
            TriggeringObjectProjection
                .shouldEmit(
                    abilityGrpId = 170349,
                    isActivatedAbility = true,
                    voidTrigger = false,
                    cardReferences = references,
                ) shouldBe false
        }

        test("void triggers retain their TriggeringObject exclusion") {
            TriggeringObjectProjection
                .shouldEmit(
                    abilityGrpId = 170349,
                    isActivatedAbility = false,
                    voidTrigger = true,
                    cardReferences = references,
                ) shouldBe false
        }

        test("Case solve trigger omits TriggeringObject by semantic metadata") {
            assertSoftly {
                references.isCaseSolveTrigger(170350) shouldBe true
                TriggeringObjectProjection
                    .shouldEmit(
                        abilityGrpId = 170350,
                        isActivatedAbility = false,
                        voidTrigger = false,
                        cardReferences = references,
                    ) shouldBe false
            }
        }
    })
