package leyline.game.event

import forge.card.CardStateName
import leyline.game.snapshot.GsmSnapshot

/**
 * Derives the events that the diff pipeline previously asked the
 * [GameEventCollector] to compute itself: P/T deltas and DFC backside flips.
 *
 * `CardSnapshot` already carries every input — `netPower`, `netToughness`,
 * `currentStateNameIsBackside` — and the diff pipeline already pairs `prev`
 * with `cur`. Synthesizing here lets the collector translate Forge events
 * 1:1 without keeping its own diff state, so there is one diffing engine
 * rather than two.
 *
 * Gates are conservative — emit only when both sides observe a creature
 * (non-null `netPower`/`netToughness`) or both sides observe the same card
 * with differing backside flag. New entries (no prev) and exits (no cur)
 * produce nothing, which matches the prior collector behavior of "establish
 * baseline silently".
 */
object SnapDeltaSynthesizer {
    fun synthesize(
        prev: GsmSnapshot,
        cur: GsmSnapshot,
    ): List<GameEvent> {
        val out = mutableListOf<GameEvent>()
        for ((fid, curCard) in cur.objects) {
            val prevCard = prev.objects[fid] ?: continue

            val prevP = prevCard.netPower
            val prevT = prevCard.netToughness
            val curP = curCard.netPower
            val curT = curCard.netToughness
            if (prevP != null && prevT != null && curP != null && curT != null) {
                if (prevP != curP || prevT != curT) {
                    out.add(
                        GameEvent.PowerToughnessChanged(
                            cardId = fid,
                            oldPower = prevP,
                            newPower = curP,
                            oldToughness = prevT,
                            newToughness = curT,
                        ),
                    )
                }
            }

            if (prevCard.currentStateNameIsBackside != curCard.currentStateNameIsBackside) {
                val newStateName =
                    if (curCard.currentStateNameIsBackside) {
                        CardStateName.Backside
                    } else {
                        CardStateName.Original
                    }
                out.add(GameEvent.CardTransformed(cardId = fid, newStateName = newStateName))
            }
        }
        return out
    }
}
