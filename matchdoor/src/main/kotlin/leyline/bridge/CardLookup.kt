package leyline.bridge

import forge.game.Game
import forge.game.GameActionUtil
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.handoff.Target
import leyline.bridge.types.ForgeCardId

internal val searchableZones =
    listOf(
        ZoneType.Hand,
        ZoneType.Battlefield,
        ZoneType.Graveyard,
        ZoneType.Exile,
        ZoneType.Library,
        ZoneType.Command,
        ZoneType.Stack,
    )

internal fun findCard(
    game: Game,
    cardId: ForgeCardId,
): Card? = game.getCardsIn(searchableZones).firstOrNull { it.id == cardId.value }

internal fun resolveTarget(
    game: Game,
    target: Target,
): forge.game.GameObject? =
    when (target) {
        is Target.Card -> findCard(game, target.cardId)
        is Target.Player -> game.getPlayer(target.playerId.value)
    }

internal fun resolveAttackDefender(
    game: Game,
    attackingPlayer: Player,
    defender: Target?,
): GameEntity? =
    when (defender) {
        is Target.Card -> {
            val card = findCard(game, defender.cardId)
            if (card != null && card.isPlaneswalker && card.controller.isOpponentOf(attackingPlayer)) card else null
        }
        is Target.Player -> {
            val playerDefender = game.getPlayer(defender.playerId.value)
            if (playerDefender != null && playerDefender.isOpponentOf(attackingPlayer)) playerDefender else null
        }
        null -> attackingPlayer.opponents.firstOrNull()
    }

/**
 * All castable spell abilities for a card, including alternative costs
 * (Overload, Flashback, Escape, etc.). Stable ordering: base ability first,
 * then alt costs in engine order.
 */
internal fun getAllCastableAbilities(
    card: Card,
    player: Player,
): List<SpellAbility> {
    // Foretold cards in exile are face-down — card.getSpells() reads from the
    // current state (face-down) which has no spells. Reach into the original
    // state to recover the underlying castable SA, then let
    // GameActionUtil.getAlternativeCosts attach the AlternativeCost.Foretold
    // wrapper. Without this, foretold cards never surface a cast action.
    val baseAbilities =
        if (card.isForetold && card.isInZone(forge.game.zone.ZoneType.Exile) && card.getSpells().isEmpty()) {
            card.getOriginalState(forge.card.CardStateName.Original)?.nonManaAbilities?.filter { it.isSpell }
                ?: emptyList()
        } else {
            card.getSpells()
        }

    val expanded = mutableListOf<SpellAbility>()
    val withAddCosts = mutableListOf<SpellAbility>()
    for (sa in baseAbilities) {
        sa.setActivatingPlayer(player)
        withAddCosts.addAll(GameActionUtil.getAdditionalCostSpell(sa))
    }
    for (sa in withAddCosts) {
        sa.setActivatingPlayer(player)
        val altCosts = GameActionUtil.getAlternativeCosts(sa, player, false)
        val (priority, other) =
            altCosts.partition { altSa ->
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

    // Plot and Foretell's hand SAs are added by Forge as KeywordInstance abilities
    // (CardFactoryUtil K:Plot: / K:Foretell:) and aren't returned by card.getSpells()
    // — append directly from card.spellAbilities so they're reachable through the
    // same index space the cast pathway uses (action emit, alt-cost resolution,
    // SpellExecutor.castSpell).
    val keywordHandSAs = card.spellAbilities.filter { it.isPlotting || it.isForetelling }
    expanded.addAll(keywordHandSAs)

    // Room door-unlock SAs aren't in card.getSpells() — Forge stores them on
    // Card.unlockAbilities[CardStateName] keyed by LeftSplit / RightSplit. Append
    // each locked door's unlock SA so CastLeftRoom / CastRightRoom share the
    // same index space as the regular cast pathway. From hand both doors are
    // locked; from battlefield only the side(s) not yet unlocked surface here.
    if (card.isRoom) {
        for (lockedState in card.lockedRooms) {
            val unlockSa = card.getUnlockAbility(lockedState) ?: continue
            unlockSa.setActivatingPlayer(player)
            expanded.add(unlockSa)
        }
    }

    return expanded.filter { it.canPlay() && it.canCastTiming(player) }
}

fun chooseCastAbility(
    card: Card,
    player: Player,
): SpellAbility? {
    val all = getAllCastableAbilities(card, player)
    if (all.isEmpty()) return null
    return all.firstOrNull { it.hasParam("WithoutManaCost") } ?: all.first()
}

/** Human-readable label for a castable ability (e.g. "Overload — {1}{R}"). */
internal fun describeCastAbility(sa: SpellAbility): String {
    val cost = sa.payCosts?.toSimpleString().orEmpty()
    val altCost = sa.alternativeCost
    return if (altCost != null) {
        "$altCost — $cost"
    } else {
        "${sa.hostCard?.name ?: "Cast"} — $cost"
    }
}

internal fun getNonManaActivatedAbilities(
    card: Card,
    player: Player,
): List<SpellAbility> {
    val abilities = mutableListOf<SpellAbility>()
    for (ability in card.spellAbilities) {
        ability.setActivatingPlayer(player)
        if (!ability.isActivatedAbility) continue
        if (ability.isManaAbility()) continue
        abilities.add(ability)
    }
    return abilities
}

internal fun getPlayableManaAbilities(
    card: Card,
    player: Player,
): List<SpellAbility> {
    val abilities = mutableListOf<SpellAbility>()
    for (ability in card.manaAbilities) {
        ability.setActivatingPlayer(player)
        if (ability.canPlay()) abilities.add(ability)
    }
    return abilities
}

/** Hand is always castable; other zones allowed if the card has mayPlay grants for the given player. */
internal fun canCastFromZone(
    card: Card,
    zone: ZoneType?,
    player: Player = card.controller,
): Boolean {
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
