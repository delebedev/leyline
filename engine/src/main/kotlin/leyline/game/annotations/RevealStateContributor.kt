package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.state.CardRevealedKind
import leyline.game.state.InstanceRevealedToOpponentKind

/** Two independent persistent views derived from semantic reveal completion. */
object RevealStateContributor : AnnotationContributor {
    override val rank: Int = 15

    override fun contribute(ctx: AnnotationContext): Contribution {
        val reveals =
            ctx.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .filter { it.viewerSeatId != it.ownerSeatId }
                .flatMap { reveal -> reveal.cardIds.map { cardId -> reveal to cardId } }
                .distinctBy { (_, cardId) -> cardId }
        val transient = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()
        val faceUp = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()

        reveals.forEach { (reveal, cardId) ->
            val proxyId = ctx.bridge.lookupRevealProxy(cardId) ?: return@forEach
            transient += AnnotationBuilder.revealedCardCreated(proxyId)
            val affectorId = revealAffector(reveal.sourceCardId, ctx) ?: return@forEach
            faceUp +=
                AnnotationBuilder.cardRevealed(
                    affectorId = affectorId,
                    revealedCardId = proxyId,
                    sourceZoneId =
                        effectiveZone(
                            cardId,
                            reveal.sourceZone?.let { ZoneIds.revealZone(it, reveal.ownerSeatId) },
                            ctx,
                        ),
                )
        }

        val opponentKnowledge = ctx.opponentKnowledge.map(AnnotationBuilder::instanceRevealedToOpponent)

        return Contribution(
            transient = transient,
            persistent =
                mapOf(
                    CardRevealedKind to faceUp,
                    InstanceRevealedToOpponentKind to opponentKnowledge,
                ),
        )
    }

    private fun effectiveZone(
        cardId: ForgeCardId,
        fallback: Int?,
        ctx: AnnotationContext,
    ): Int =
        ctx.snap.zones.values
            .firstOrNull { cardId in it.contents }
            ?.id ?: fallback ?: 0

    private fun revealAffector(
        sourceCardId: ForgeCardId?,
        ctx: AnnotationContext,
    ): InstanceId? {
        if (sourceCardId == null) return null
        ctx.snap.stack.entries.lastOrNull { !it.isSpell && it.forgeCardId == sourceCardId }?.let { entry ->
            return if (entry.forgeAbilityId != 0) {
                ctx.frameIds.triggerStackAbilityIid(entry.forgeAbilityId)
            } else {
                ctx.frameIds.stackAbilityIid(sourceCardId)
            }
        }
        val resolved = ctx.events.filterIsInstance<GameEvent.SpellResolved>().lastOrNull { it.cardId == sourceCardId }
        if (resolved?.isAbility == true || resolved?.isTrigger == true) {
            return InstanceId(ctx.stackAbilityIid(resolved.abilityForgeId, sourceCardId))
        }
        val resolvingTransfer =
            ctx.transferResult
                ?.transfers
                ?.lastOrNull { it.forgeCardId == sourceCardId && it.category == TransferCategory.Resolve }
        return resolvingTransfer?.let { InstanceId(it.origId) } ?: ctx.frameIds.cardIid(sourceCardId).takeIf { it.value != 0 }
    }
}
