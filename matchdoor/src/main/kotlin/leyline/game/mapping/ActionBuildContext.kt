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
