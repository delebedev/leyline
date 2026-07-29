package leyline.game.mapping

import leyline.game.PriorityActionSet
import leyline.game.PriorityActionValue

internal class PriorityActionSetBuilder {
    private val active = mutableListOf<PriorityActionValue>()
    private val inactive = mutableListOf<PriorityActionValue>()

    fun addAction(value: PriorityActionValue) = apply { active += value }

    fun addAllActions(values: Iterable<PriorityActionValue>) = apply { active += values }

    fun addInactiveAction(value: PriorityActionValue) = apply { inactive += value }

    fun addAllInactiveActions(values: Iterable<PriorityActionValue>) = apply { inactive += values }

    fun build(): PriorityActionSet = PriorityActionSet(active.toList(), inactive.toList())
}
