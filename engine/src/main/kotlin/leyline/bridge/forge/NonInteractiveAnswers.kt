package leyline.bridge.forge

import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import leyline.bridge.NonInteractiveScope.Policy
import leyline.bridge.coord.ConvokeShardAssigner

/**
 * Deterministic answers for Forge payment callbacks inside a
 * [leyline.bridge.NonInteractiveScope].
 *
 * QUIET answers "nothing chosen" so cost calculation returns the cost after
 * state-derived modifications only. BEST_EFFORT answers the maximum legal
 * reduction so payability probes see the best case the board allows.
 *
 * Sacrifice-backed reductions (Offering, Emerge) are answered empty under
 * both policies by [PlayerController.choosePermanentsToSacrifice]: Forge
 * applies the sacrifice bookkeeping (`setUsedToPay`, `setSacrificedAsOffering`)
 * even during test calculations, so a non-empty answer would leak payment
 * state out of a probe.
 */
internal object NonInteractiveAnswers {
    fun cardsForConvokeOrImprovise(
        policy: Policy,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        artifacts: Boolean,
        maxReduction: Int?,
    ): Map<Card, ManaCostShard> =
        when (policy) {
            Policy.QUIET -> emptyMap()
            Policy.BEST_EFFORT ->
                if (artifacts) {
                    val cap = minOf(manaCost.genericCost, maxReduction ?: Int.MAX_VALUE)
                    untappedCards.take(cap).associateWith { ManaCostShard.GENERIC }
                } else {
                    ConvokeShardAssigner
                        .assign(untappedCards.toList(), ConvokeShardAssigner.costCounts(manaCost)) { it.color }
                        .toMap()
                }
        }

    fun cardsToDelve(
        policy: Policy,
        genericAmount: Int,
        grave: CardCollection,
    ): CardCollectionView =
        when (policy) {
            Policy.QUIET -> CardCollection()
            Policy.BEST_EFFORT -> CardCollection(grave.take(genericAmount))
        }

    fun numberForCostReduction(
        policy: Policy,
        min: Int,
        max: Int,
    ): Int =
        when (policy) {
            Policy.QUIET -> min
            Policy.BEST_EFFORT -> max
        }
}
