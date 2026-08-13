package leyline.game.state

import leyline.bridge.types.ForgeCardId

/**
 * Complete synthetic-effect lifecycle state for one projection transition.
 *
 * A [Planner] is local to a compile attempt. Only its [freeze] result may be
 * installed on [GameBridge], together with the matching identity and reveal
 * transitions. Scoped live effect observations enter separately as immutable
 * [EffectProjectionFacts] values; this state carries only tentative lifecycle
 * allocations and prior committed effect state.
 */
data class SyntheticEffectProjection(
    val effects: EffectTracker.State,
    val crew: SyntheticEffectLifecycle.State<ForgeCardId>,
    val reconfigure: SyntheticEffectLifecycle.State<ForgeCardId>,
    val mutate: SyntheticEffectLifecycle.State<Pair<Int, Int>>,
    val earthbend: EarthbendTracker.State,
) {
    companion object {
        fun initial(): SyntheticEffectProjection {
            val effects = EffectTracker()
            val crew = SyntheticEffectLifecycle<ForgeCardId> { error("No allocation during initial state") }
            val mutate = SyntheticEffectLifecycle<Pair<Int, Int>> { error("No allocation during initial state") }
            val earthbend = EarthbendTracker()
            return SyntheticEffectProjection(effects.freeze(), crew.freeze(), crew.freeze(), mutate.freeze(), earthbend.freeze())
        }
    }

    class Planner(
        state: SyntheticEffectProjection,
    ) {
        val effects = EffectTracker().also { it.load(state.effects) }
        val crew = SyntheticEffectLifecycle<ForgeCardId> { effects.nextEffectId() }.also { it.load(state.crew) }
        val reconfigure = SyntheticEffectLifecycle<ForgeCardId> { effects.nextEffectId() }.also { it.load(state.reconfigure) }
        val mutate = SyntheticEffectLifecycle<Pair<Int, Int>> { effects.nextEffectId() }.also { it.load(state.mutate) }
        val earthbend = EarthbendTracker().also { it.load(state.earthbend) }

        fun freeze(): SyntheticEffectProjection =
            SyntheticEffectProjection(
                effects = effects.freeze(),
                crew = crew.freeze(),
                reconfigure = reconfigure.freeze(),
                mutate = mutate.freeze(),
                earthbend = earthbend.freeze(),
            )
    }
}
