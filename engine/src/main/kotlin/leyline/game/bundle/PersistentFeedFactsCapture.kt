package leyline.game.bundle

import forge.game.card.CardLists
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.StateProjectionEnvironment
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.PromptProjectionFacts

/** Shell-only materializer for time-sensitive persistent-feed observations. */
object PersistentFeedFactsCapture {
    fun capture(
        snapshot: GsmSnapshot,
        promptFacts: PromptProjectionFacts,
        bridge: GameBridge,
        environment: StateProjectionEnvironment,
    ): PersistentFeedFacts =
        PersistentFeedFacts(
            combatQualifications =
                CombatQualificationFactsCapture.scan(
                    snapshot,
                    bridge,
                    environment.cardReferences,
                ),
            collectEvidence = collectEvidence(promptFacts, bridge, environment),
            endStepTokenSources = endStepTokenSources(snapshot, bridge),
        )

    private fun collectEvidence(
        promptFacts: PromptProjectionFacts,
        bridge: GameBridge,
        environment: StateProjectionEnvironment,
    ): List<PersistentFeedFacts.CollectEvidenceDisplay> =
        promptFacts.collectEvidenceCosts.mapNotNull { fact ->
            val source = bridge.findCard(fact.context.sourceForgeCardId) ?: return@mapNotNull null
            val controller = source.controller ?: return@mapNotNull null
            val abilityGrpId = environment.cardReferences.collectEvidenceAbilityGrpId(source.name)
            if (abilityGrpId == 0) return@mapNotNull null
            PersistentFeedFacts.CollectEvidenceDisplay(
                key = fact.key,
                sourceForgeCardId = fact.context.sourceForgeCardId,
                threshold = fact.context.threshold,
                graveyardManaValue = CardLists.getTotalCMC(controller.getCardsIn(ZoneType.Graveyard)),
                abilityGrpId = abilityGrpId,
            )
        }

    private fun endStepTokenSources(
        snapshot: GsmSnapshot,
        bridge: GameBridge,
    ): List<PersistentFeedFacts.EndStepTokenSource> =
        snapshot.objects.values
            .filter { it.isOnBattlefield && it.endOfTurnLeavePlay }
            .map { token ->
                val source =
                    bridge
                        .findCard(token.forgeCardId)
                        ?.tokenSpawningAbility
                        ?.hostCard
                        ?.let { ForgeCardId(it.id) }
                PersistentFeedFacts.EndStepTokenSource(token.forgeCardId, source)
            }
}
