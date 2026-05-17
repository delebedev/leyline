package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent

class StateMapperTrainingTest :
    FunSpec({
        tags(UnitTag)

        test("later unrelated +1/+1 counter does not inherit Training affector") {
            val trainer = ForgeCardId(101)
            val trainingCounter = GameEvent.CountersChanged(trainer, "P1P1", oldCount = 0, newCount = 1)
            val trainingResolved =
                GameEvent.SpellResolved(
                    cardId = trainer,
                    hasFizzled = false,
                    isTrigger = true,
                    abilityForgeId = 10,
                    abilityGrpId = KeywordAbilityIds.TRAINING,
                )
            val unrelatedCounter = GameEvent.CountersChanged(trainer, "P1P1", oldCount = 1, newCount = 2)
            val unrelatedResolved =
                GameEvent.SpellResolved(
                    cardId = trainer,
                    hasFizzled = false,
                    isTrigger = true,
                    abilityForgeId = 11,
                    abilityGrpId = 999,
                )
            val events = listOf(trainingCounter, trainingResolved, unrelatedCounter, unrelatedResolved)

            StateMapper.trainingResolutionForCounterEvent(0, trainingCounter, events)?.abilityForgeId shouldBe 10
            StateMapper.trainingResolutionForCounterEvent(2, unrelatedCounter, events).shouldBeNull()
        }
    })
