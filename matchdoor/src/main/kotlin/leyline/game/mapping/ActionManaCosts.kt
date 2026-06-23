package leyline.game.mapping

import forge.ai.ComputerUtilMana
import forge.card.mana.ManaCost
import forge.game.cost.CostAdjustment
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.types.ManaColorMapping
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import forge.game.zone.ZoneType as ForgeZoneType

internal object ActionManaCosts {
    fun canPayManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean =
        try {
            ComputerUtilMana.canPayManaCost(sa, player, 0, false) || canPayOrTwoGenericManaCost(sa, player)
        } catch (_: Exception) {
            canPayOrTwoGenericManaCost(sa, player)
        }

    fun canPlayAndPayManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean =
        try {
            sa.canPlay() && canPayManaCost(sa, player)
        } catch (_: Exception) {
            false
        }

    @Suppress("CyclomaticComplexMethod")
    private fun canPayOrTwoGenericManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val cost = computeEffectiveCost(sa, player) ?: return false
        val hybridColors = cost.mapNotNull { ManaColorMapping.fromOrTwoGenericShard(it) }
        if (hybridColors.isEmpty()) return false

        val coloredRequirements =
            cost.mapNotNull { shard ->
                if (ManaColorMapping.fromOrTwoGenericShard(shard) == null) ManaColorMapping.fromShard(shard) else null
            }
        val sourceColors = availableManaSourceColors(player)
        if (sourceColors.isEmpty()) return false

        fun canPayColor(
            sourceIndex: Int,
            color: ManaColor,
        ): Boolean = ManaColor.Generic in sourceColors[sourceIndex] || color in sourceColors[sourceIndex]

        fun payGeneric(
            needed: Int,
            used: BooleanArray,
            then: () -> Boolean,
        ): Boolean {
            if (needed == 0) return then()
            for (i in sourceColors.indices) {
                if (used[i]) continue
                used[i] = true
                if (payGeneric(needed - 1, used, then)) return true
                used[i] = false
            }
            return false
        }

        fun payHybrids(
            index: Int,
            used: BooleanArray,
        ): Boolean {
            if (index == hybridColors.size) {
                return payGeneric(cost.genericCost, used) { true }
            }
            val color = hybridColors[index]
            for (i in sourceColors.indices) {
                if (used[i] || !canPayColor(i, color)) continue
                used[i] = true
                if (payHybrids(index + 1, used)) return true
                used[i] = false
            }
            return payGeneric(2, used) { payHybrids(index + 1, used) }
        }

        fun payColored(
            index: Int,
            used: BooleanArray,
        ): Boolean {
            if (index == coloredRequirements.size) return payHybrids(0, used)
            val color = coloredRequirements[index]
            for (i in sourceColors.indices) {
                if (used[i] || !canPayColor(i, color)) continue
                used[i] = true
                if (payColored(index + 1, used)) return true
                used[i] = false
            }
            return false
        }

        return payColored(0, BooleanArray(sourceColors.size))
    }

    private fun availableManaSourceColors(player: Player): List<Set<ManaColor>> =
        player
            .getZone(ForgeZoneType.Battlefield)
            .cards
            .filterNot { it.isTapped }
            .mapNotNull { card ->
                getPlayableManaAbilities(card, player)
                    .flatMap { sa ->
                        val mana = sa.manaPart ?: return@flatMap emptyList()
                        val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
                        produced.split(" ").mapNotNull { producedToManaColor(it) }
                    }.toSet()
                    .takeIf { it.isNotEmpty() }
            }

    fun computeEffectiveCost(
        sa: SpellAbility,
        player: Player,
    ): ManaCost? {
        val baseCost = sa.payCosts ?: return null
        val hostCard = sa.hostCard
        val originalActivator = sa.activatingPlayer
        if (originalActivator == null) sa.setActivatingPlayer(player)
        val originalCastFrom = hostCard?.castFrom
        val seededCastFrom =
            hostCard?.isCommander == true &&
                originalCastFrom == null &&
                hostCard.zone?.zoneType == ForgeZoneType.Command
        if (seededCastFrom) hostCard?.setCastFrom(hostCard.zone)
        try {
            val adjusted = CostAdjustment.adjust(baseCost, sa, false)
            val manaCost = adjusted.totalMana ?: return null
            if (manaCost.isNoCost) return null
            val beingPaid = ManaCostBeingPaid(manaCost)
            CostAdjustment.adjust(beingPaid, sa, player, null, true, false)
            return beingPaid.toManaCost()
        } finally {
            if (seededCastFrom) hostCard?.setCastFrom(originalCastFrom)
            if (originalActivator == null) sa.setActivatingPlayer(null)
        }
    }

    fun forgeManaCostToPairs(manaCost: ManaCost): List<Pair<ManaColor, Int>> = ManaColorMapping.deriveManaCostWithGenericLast(manaCost)

    fun addManaCostFromForge(
        manaCost: ManaCost,
        actionBuilder: Action.Builder,
        abilityGrpId: Int? = null,
    ) {
        forgeManaCostToRequirements(manaCost, abilityGrpId).forEach(actionBuilder::addManaCost)
    }

    fun forgeManaCostToRequirements(
        manaCost: ManaCost,
        abilityGrpId: Int? = null,
    ): List<ManaRequirement> {
        if (manaCost.none { ManaColorMapping.fromOrTwoGenericShard(it) != null }) {
            return aggregatedManaRequirements(manaCost, abilityGrpId)
        }
        val result = mutableListOf<ManaRequirement>()
        for (shard in manaCost) {
            val hybridColor = ManaColorMapping.fromOrTwoGenericShard(shard)
            val color = hybridColor ?: ManaColorMapping.fromShard(shard) ?: continue
            val req = ManaRequirement.newBuilder().setCount(1)
            if (hybridColor != null) req.addColor(ManaColor.TwoGeneric)
            req.addColor(color)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            result.add(req.build())
        }
        val generic = manaCost.genericCost
        if (generic > 0) {
            val req = ManaRequirement.newBuilder().addColor(ManaColor.Generic).setCount(generic)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            result.add(req.build())
        }
        return result
    }

    private fun aggregatedManaRequirements(
        manaCost: ManaCost,
        abilityGrpId: Int?,
    ): List<ManaRequirement> {
        val result = mutableListOf<ManaRequirement>()
        for ((color, count) in ManaColorMapping.colorCounts(manaCost)) {
            val req = ManaRequirement.newBuilder().addColor(color).setCount(count)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            result.add(req.build())
        }
        val generic = manaCost.genericCost
        if (generic > 0) {
            val req = ManaRequirement.newBuilder().addColor(ManaColor.Generic).setCount(generic)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            result.add(req.build())
        }
        return result
    }

    fun producedToManaColor(produced: String): ManaColor? = ManaColorMapping.fromProduced(produced)
}
