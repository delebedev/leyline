package leyline.game.snapshot

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.SpellAbilityStackInstance
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneMapper
import leyline.game.state.GameBridge
import org.jetbrains.annotations.VisibleForTesting

internal object StackAbilityGrpIdResolver {
    /**
     * Resolve the **ability** grpId for a stack entry — the row in the Arena
     * `Abilities` table that describes this trigger / activated SA. Resolution
     * order:
     *  1. Saga chapter (per-chapter ability id from CardData.chapterAbilityGrpIds).
     *  2. Trigger registry row when the card database has an exact trigger match.
     *  3. Cascade / Training keyword ids for triggers without per-card registry rows.
     *  4. Default fallback → [sourceCardGrpId]. Preserves pre-fix behavior for SAs
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
    ): Int {
        resolveChapterGrpId(entry, sourceCard, bridge)?.let { return it }
        if (entry.isTrigger && sourceCardGrpId != 0) {
            knownKeywordTriggerGrpId(entry, sourceCard, sourceCardGrpId, bridge)?.let { return it }
            val cardData = bridge.cardRepository.findByGrpId(sourceCardGrpId)
            val registry = if (cardData != null) bridge.abilityRegistryFor(sourceCard, cardData) else null
            entry.spellAbility?.trigger?.id?.let { triggerId ->
                registry?.forTrigger(triggerId)?.takeIf { it != 0 }?.let { return it }
            }
        }
        if (entry.isTrigger) cascadeOrTrainingGrpId(entry, sourceCard)?.let { return it }
        // Discover (Forge `DB$ Discover | Num$ N`): per-card Arena ability row.
        // Pick the first Triggered ability on the source card; for cards with a
        // single triggered ability (Etali's Favor, Geological Appraiser, Trumpeting
        // Carnosaur) this resolves correctly. Multi-triggered cards need a
        // per-card disambiguation pass.
        if (entry.isTrigger && entry.spellAbility?.api == ApiType.Discover && sourceCardGrpId != 0) {
            val cardData = bridge.cardRepository.findByGrpId(sourceCardGrpId)
            val triggeredAbility =
                cardData?.abilityIds?.firstOrNull { (id, _) ->
                    bridge.cardRepository.findAbilityInfo(id)?.category == 2
                }
            triggeredAbility?.first?.let { return it }
        }
        return sourceCardGrpId
    }

    private fun knownKeywordTriggerGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? =
        decayedTriggerGrpId(entry, sourceCard, sourceCardGrpId, bridge)
            ?: backupTriggerGrpId(entry, sourceCardGrpId, bridge)
            ?: KeywordAbilityIds.MENTOR.takeIf { isMentorTrigger(entry) }

    private fun cascadeOrTrainingGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Int? =
        when {
            sourceCard.hasKeyword("Cascade") -> KeywordAbilityIds.CASCADE
            entry.spellAbility?.hasParam("Training") == true && entry.spellAbility?.api == ApiType.PutCounter -> KeywordAbilityIds.TRAINING
            else -> null
        }

    private fun backupTriggerGrpId(
        entry: SpellAbilityStackInstance,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? {
        if (!isBackupTrigger(entry)) return null
        return bridge.cardRepository.findKeywordAbilityGrpId(sourceCardGrpId, KeywordAbilityIds.BACKUP)
    }

    private fun isBackupTrigger(entry: SpellAbilityStackInstance): Boolean {
        val sa = entry.spellAbility ?: return false
        return sa.isBackup || sa.trigger?.getParam("TriggerDescription")?.startsWith("Backup ") == true
    }

    private fun isMentorTrigger(entry: SpellAbilityStackInstance): Boolean {
        val sa = entry.spellAbility ?: return false
        return sa.trigger?.getParam("TriggerDescription")?.startsWith("Mentor") == true
    }

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
