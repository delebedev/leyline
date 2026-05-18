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

            StateMapper.keywordCounterResolutionForEvent(0, trainingCounter, events)?.abilityForgeId shouldBe 10
            StateMapper.keywordCounterResolutionForEvent(2, unrelatedCounter, events).shouldBeNull()
        }

        test("Backup counter on another target inherits resolving ability affector") {
            val source = ForgeCardId(101)
            val target = ForgeCardId(202)
            val backupOne = 166477
            val counter = GameEvent.CountersChanged(target, "P1P1", oldCount = 0, newCount = 1)
            val resolved =
                GameEvent.SpellResolved(
                    cardId = source,
                    hasFizzled = false,
                    isTrigger = true,
                    abilityForgeId = 12,
                    abilityGrpId = backupOne,
                )
            val events = listOf(counter, resolved)

            StateMapper
                .keywordCounterResolutionForEvent(0, counter, events) { it.abilityGrpId == backupOne }
                ?.abilityForgeId shouldBe 12
        }

        test("earlier counter before Backup resolution does not inherit affector when another counter intervenes") {
            val source = ForgeCardId(101)
            val firstTarget = ForgeCardId(202)
            val secondTarget = ForgeCardId(303)
            val backupOne = 166477
            val firstCounter = GameEvent.CountersChanged(firstTarget, "P1P1", oldCount = 0, newCount = 1)
            val secondCounter = GameEvent.CountersChanged(secondTarget, "P1P1", oldCount = 0, newCount = 1)
            val resolved =
                GameEvent.SpellResolved(
                    cardId = source,
                    hasFizzled = false,
                    isTrigger = true,
                    abilityForgeId = 12,
                    abilityGrpId = backupOne,
                )
            val events = listOf(firstCounter, secondCounter, resolved)

            StateMapper
                .keywordCounterResolutionForEvent(0, firstCounter, events) { it.abilityGrpId == backupOne }
                .shouldBeNull()
            StateMapper
                .keywordCounterResolutionForEvent(1, secondCounter, events) { it.abilityGrpId == backupOne }
                ?.abilityForgeId shouldBe 12
        }
    })
