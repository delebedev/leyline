package forge.nexus.bridge

import forge.game.Game
import forge.game.GameActionUtil
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType

internal val searchableZones = listOf(
    ZoneType.Hand,
    ZoneType.Battlefield,
    ZoneType.Graveyard,
    ZoneType.Exile,
    ZoneType.Library,
    ZoneType.Command,
    ZoneType.Stack,
)

internal fun findCard(game: Game, cardId: Int): Card? =
    game.getCardsIn(searchableZones).firstOrNull { it.id == cardId }

internal fun resolveTarget(game: Game, target: TargetDto): forge.game.GameObject? = when (target.kind) {
    "player" -> game.getPlayer(target.id)
    "card" -> findCard(game, target.id)
    "stack_item" -> game.getStack().firstOrNull { it.id == target.id }?.getSpellAbility()
    else -> null
}

internal fun resolveAttackDefender(
    game: Game,
    attackingPlayer: Player,
    defenderPlayerId: Int?,
    defenderCardId: Int? = null,
): GameEntity? {
    if (defenderCardId != null) {
        val card = findCard(game, defenderCardId) ?: return null
        return if (card.isPlaneswalker && card.controller.isOpponentOf(attackingPlayer)) card else null
    }
    if (defenderPlayerId != null) {
        val playerDefender = game.getPlayer(defenderPlayerId) ?: return null
        return if (playerDefender.isOpponentOf(attackingPlayer)) playerDefender else null
    }
    return attackingPlayer.opponents.firstOrNull()
}

/**
 * All castable spell abilities for a card, including alternative costs
 * (Overload, Flashback, Escape, etc.). Stable ordering: base ability first,
 * then alt costs in engine order.
 */
internal fun getAllCastableAbilities(card: Card, player: Player): List<SpellAbility> {
    val baseAbilities = card.getSpells()
    if (baseAbilities.isEmpty()) return emptyList()

    val withAddCosts = mutableListOf<SpellAbility>()
    for (sa in baseAbilities) {
        sa.setActivatingPlayer(player)
        withAddCosts.addAll(GameActionUtil.getAdditionalCostSpell(sa))
    }

    val expanded = mutableListOf<SpellAbility>()
    for (sa in withAddCosts) {
        sa.setActivatingPlayer(player)
        val altCosts = GameActionUtil.getAlternativeCosts(sa, player, false)
        val (priority, other) = altCosts.partition { altSa ->
            sa.payCosts != null &&
                sa.payCosts.isOnlyManaCost &&
                altSa.payCosts != null &&
                altSa.payCosts.isOnlyManaCost &&
                sa.payCosts.totalMana.compareTo(altSa.payCosts.totalMana) == 1
        }
        expanded.addAll(priority)
        expanded.add(sa)
        expanded.addAll(other)
    }

    return expanded.filter { it.canPlay() && it.canCastTiming(player) }
}

fun chooseCastAbility(card: Card, player: Player): SpellAbility? {
    val all = getAllCastableAbilities(card, player)
    if (all.isEmpty()) return null
    return all.firstOrNull { it.hasParam("WithoutManaCost") } ?: all.first()
}

/** Human-readable label for a castable ability (e.g. "Overload — {1}{R}"). */
internal fun describeCastAbility(sa: SpellAbility): String {
    val cost = sa.payCosts?.toSimpleString() ?: ""
    val altCost = sa.alternativeCost
    return if (altCost != null) {
        "$altCost — $cost"
    } else {
        "${sa.hostCard?.name ?: "Cast"} — $cost"
    }
}

internal fun getNonManaActivatedAbilities(card: Card, player: Player): List<SpellAbility> {
    val abilities = mutableListOf<SpellAbility>()
    for (ability in card.spellAbilities) {
        ability.setActivatingPlayer(player)
        if (!ability.isActivatedAbility) continue
        if (ability.isManaAbility()) continue
        abilities.add(ability)
    }
    return abilities
}

/** Hand is always castable; other zones allowed if the card has mayPlay grants for the given player. */
internal fun canCastFromZone(card: Card, zone: ZoneType?, player: Player = card.controller): Boolean {
    if (zone == null) return false
    if (zone == ZoneType.Hand) return true
    return card.mayPlay(player).isNotEmpty()
}

internal fun extractLoyaltyCost(ability: SpellAbility): String? {
    val costStr = ability.payCosts?.toSimpleString() ?: return null
    val match = Regex("""^[+-]?\d+$""").find(costStr.trim())
    if (match != null) return costStr.trim()
    return null
}

internal fun abilityLabel(ability: SpellAbility): String {
    val description = ability.description?.trim()?.takeIf { it.isNotBlank() }
    if (description != null) return description
    val stackDescription = ability.stackDescription?.trim()?.takeIf { it.isNotBlank() }
    if (stackDescription != null) return stackDescription
    return "Activated ability"
}
