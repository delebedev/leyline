package leyline.game.snapshot

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.SpellAbilityStackInstance
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneMapper
import leyline.game.state.AbilityRegistry
import leyline.game.state.GameBridge
import org.jetbrains.annotations.VisibleForTesting

internal object StackAbilityGrpIdResolver {
    /**
     * Resolve the **ability** grpId for a stack entry — the row in the Arena
     * `Abilities` table that describes this trigger / activated SA. Resolution
     * order:
     *  1. Saga chapter (trigger-row grpId from CardData; see
     *     `ZoneMapper.chapterGrpIdFromCardData`).
     *  2. Runtime-keyed identity recorded by the event lifecycle.
     *  3. Typed definition lookup for entries that bypassed that lifecycle.
     *  4. Explicit mechanic fallbacks for stack-only synthetic entries.
     *  5. Default fallback → [sourceCardGrpId]. Preserves behavior for SAs
     *     whose ability id we don't yet resolve.
     *
     * Returns 0 only when [sourceCardGrpId] is itself 0 (no Arena printing for
     * the source card); callers apply [GameBridge.FALLBACK_GRPID].
     */
    @VisibleForTesting
    internal fun resolveEntryAbilityGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int =
        resolveChapterGrpId(entry, sourceCard, bridge)
            ?: resolveParadigmDelayedGrpId(entry, sourceCard)
            ?: pendingIdentityGrpId(entry, bridge)
            ?: resolveStructuredIdentityGrpId(entry, sourceCard, bridge)
            ?: decayedTriggerGrpId(entry, sourceCard, sourceCardGrpId, bridge)
            ?: cascadeOrTrainingGrpId(entry, sourceCard)
            ?: resolveDiscoverGrpId(entry, sourceCardGrpId, bridge)
            ?: sourceCardGrpId

    private fun pendingIdentityGrpId(
        entry: SpellAbilityStackInstance,
        bridge: GameBridge,
    ): Int? =
        entry.spellAbility
            ?.id
            ?.let { bridge.stackAbilityIdentity(it)?.abilityGrpId }
            ?.takeIf { it != 0 }

    private fun resolveParadigmDelayedGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Int? = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER.takeIf { entry.isTrigger && isParadigmDelayedTrigger(entry, sourceCard) }

    private fun resolveStructuredIdentityGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        bridge: GameBridge,
    ): Int? {
        val ability = entry.spellAbility ?: return null
        return bridge.resolveAbilityIdentity(sourceCard, ability)?.abilityGrpId?.takeIf { it != 0 }
    }

    /** Discover (Forge `DB$ Discover | Num$ N`): per-card ability row. */
    private fun resolveDiscoverGrpId(
        entry: SpellAbilityStackInstance,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? {
        if (!entry.isTrigger || entry.spellAbility?.api != ApiType.Discover || sourceCardGrpId == 0) return null
        val cardData = bridge.cardRepository.findByGrpId(sourceCardGrpId) ?: return null
        return cardData
            .abilityIds
            .firstOrNull { (id, _) -> bridge.cardRepository.findAbilityInfo(id)?.category == AbilityRegistry.TRIGGER_CATEGORY }
            ?.first
    }

    private fun cascadeOrTrainingGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Int? =
        when {
            sourceCard.hasKeyword("Cascade") -> KeywordAbilityIds.CASCADE
            entry.spellAbility?.hasParam("Training") == true && entry.spellAbility?.api == ApiType.PutCounter -> KeywordAbilityIds.TRAINING
            else -> null
        }

    private fun isParadigmDelayedTrigger(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Boolean =
        entry.spellAbility?.trigger?.getParam("Execute") == "ParadigmCopy" &&
            sourceCard.effectSource?.hasKeyword("Paradigm") == true

    private fun decayedTriggerGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? {
        if (!sourceCard.hasKeyword("Decayed")) return null
        val sa = entry.spellAbility ?: return null
        if (sa.api == ApiType.DelayedTrigger && sa.trigger?.getParam("Mode") == "Attacks") {
            return KeywordAbilityIds.DECAYED
        }
        if (sa.api == ApiType.Sacrifice && sa.trigger?.getParam("Phase") == "EndCombat") {
            return bridge.cardRepository.findHiddenTriggeredAbilityGrpId(sourceCardGrpId)
        }
        return null
    }

    /** If [entry] is a Saga chapter trigger, return the chapter-specific ability grpId. */
    private fun resolveChapterGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        bridge: GameBridge,
    ): Int? {
        if (!entry.isTrigger) return null
        val sa = entry.spellAbility ?: return null
        val trigger = sa.trigger ?: return null
        val chapterParam = trigger.getParam("Chapter") ?: return null
        val chapterIdx = chapterParam.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val sourceGrpId = bridge.cardRepository.findGrpIdByName(sourceCard.name) ?: return null
        val cardData = bridge.cardRepository.findByGrpId(sourceGrpId) ?: return null
        return ZoneMapper.chapterGrpIdFromCardData(cardData, chapterIdx)
    }
}
