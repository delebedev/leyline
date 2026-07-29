package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.types.GrpId
import leyline.game.PriorityAutoTapSolutionValue
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry

/**
 * Narrow dependency carrier for action emitters.
 *
 * Keeps resolver plumbing explicit while preventing extracted helpers from
 * reaching for broad bridge or snapshot state. Action-family policy stays in
 * focused preparers and [ActionMapper]; this context only performs semantic
 * card-data and ability lookups for an already-selected action source.
 */
internal data class ActionBuildContext(
    val player: Player,
    val grpIdResolver: (Card) -> GrpId,
    val cardDataLookup: (GrpId) -> CardData?,
    val abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
) {
    fun grpId(card: Card): Int = grpIdResolver(card).value

    fun cardData(grpId: Int): CardData? = cardDataLookup(GrpId(grpId))

    fun abilityRegistry(
        card: Card,
        cardData: CardData?,
    ): AbilityRegistry? = abilityRegistryLookup(card, cardData)

    fun autoTapSolution(manaCost: ManaCost): PriorityAutoTapSolutionValue? = ActionAutoTapSupport.build(manaCost, this)
}
