package leyline.game.annotations

import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.WubrgColorMapping
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.GameBridge

/**
 * Projects ability-word state whose lifetime is owned by a live stack item and
 * whose value comes from that cast's payment record.
 */
object CastAbilityWordScanner {
    private const val OPUS = "Opus"
    private const val CONVERGE = "Converge"

    fun scan(
        game: Game,
        bridge: GameBridge,
    ): List<AbilityWordScanner.AbilityWordEntry> {
        val frameIds = FrameIdResolver(bridge)
        return game.stack.mapNotNull { entry ->
            val ability = entry.spellAbility ?: return@mapNotNull null
            val source = entry.sourceCard ?: return@mapNotNull null
            when {
                isConvergeTrigger(ability, source) -> colorsSpentEntry(source, source.castSA, frameIds, ability.id)
                entry.isSpell && source.hasConverge() -> colorsSpentEntry(source, ability, frameIds, cardBacked = true)
                triggerStartsWith(ability, OPUS) -> opusEntry(ability, bridge, frameIds)
                else -> null
            }
        }
    }

    private fun colorsSpentEntry(
        source: Card,
        paidAbility: SpellAbility?,
        frameIds: FrameIdResolver,
        stackAbilityForgeId: Int? = null,
        cardBacked: Boolean = false,
    ): AbilityWordScanner.AbilityWordEntry? {
        val colors = paidColors(paidAbility)
        if (colors.isEmpty()) return null
        val iid =
            stackAbilityForgeId
                ?.let { frameIds.triggerStackAbilityIid(it).value }
                ?: frameIds.cardIid(ForgeCardId(source.id)).value
        return AbilityWordScanner.AbilityWordEntry(
            instanceId = iid,
            abilityWordName = "ColorsSpentToCast",
            colors = colors,
            forgeCardId = ForgeCardId(source.id).takeIf { cardBacked },
        )
    }

    private fun opusEntry(
        ability: SpellAbility,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): AbilityWordScanner.AbilityWordEntry? {
        val triggeringCard = ability.getTriggeringObject(AbilityKey.Card) as? Card ?: return null
        if ((triggeringCard.castSA?.totalManaSpent ?: 0) < 5) return null
        val iid = frameIds.triggerStackAbilityIid(ability.id).value
        val seat = bridge.seatOf(ability.activatingPlayer)?.value ?: return null
        return AbilityWordScanner.AbilityWordEntry(
            instanceId = iid,
            abilityWordName = OPUS,
            affectorId = seat,
            affectedIds = listOf(iid),
        )
    }

    private fun triggerStartsWith(
        ability: SpellAbility,
        word: String,
    ): Boolean = ability.trigger?.getParam("TriggerDescription")?.startsWith("$word —") == true

    private fun isConvergeTrigger(
        ability: SpellAbility,
        source: Card,
    ): Boolean =
        source.hasConverge() &&
            (ability.sourceTriggerDefinitionId > 0 || triggerStartsWith(ability, CONVERGE))

    private fun paidColors(ability: SpellAbility?): List<Int> =
        ability
            ?.payingMana
            .orEmpty()
            .flatMap { mana -> WubrgColorMapping.manaColorNumbersFromMagicMask(mana.color.toInt()) }
            .distinct()
            .sorted()
}
