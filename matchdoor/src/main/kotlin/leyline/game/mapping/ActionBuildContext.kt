package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution

/**
 * Narrow dependency carrier for action emitters.
 *
 * Keeps resolver plumbing explicit while preventing extracted helpers from
 * reaching for broad bridge or snapshot state. Action-family policy stays in
 * [ActionMapper]; this context only answers identity, card-data, ability, and
 * auto-tap questions for an already-selected action source.
 */
internal data class ActionBuildContext(
    val player: Player,
    val idResolver: (ForgeCardId) -> InstanceId,
    val grpIdResolver: (Card) -> GrpId,
    val cardDataLookup: (GrpId) -> CardData?,
    val abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
) {
    fun instanceId(card: Card): Int = idResolver(ForgeCardId(card.id)).value

    fun grpId(card: Card): Int = grpIdResolver(card).value

    fun cardData(grpId: Int): CardData? = cardDataLookup(GrpId(grpId))

    fun abilityRegistry(
        card: Card,
        cardData: CardData?,
    ): AbilityRegistry? = abilityRegistryLookup(card, cardData)

    fun autoTapSolution(manaCost: ManaCost): AutoTapSolution? = ActionAutoTapSupport.build(manaCost, this)
}
