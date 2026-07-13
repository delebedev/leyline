package leyline.bridge

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType

/** One Forge-owned traversal of candidates available during a priority window. */
class PriorityActionCandidates private constructor(
    private val byCardId: Map<Int, CardCandidates>,
) {
    data class CardCandidates(
        val card: Card,
        val casts: List<SpellAbility>,
        val activations: List<SpellAbility>,
        val manaAbilities: List<SpellAbility>,
        val landAbility: LandAbility?,
        val mdfcLandAbility: LandAbility?,
    )

    fun forCard(card: Card): CardCandidates =
        byCardId[card.id]
            ?: CardCandidates(card, emptyList(), emptyList(), emptyList(), null, null)

    fun hasLegalNonManaAction(player: Player): Boolean =
        byCardId.values.any { candidate ->
            candidate.casts.isNotEmpty() ||
                candidate.activations.any { it.canPlay() } ||
                candidate.landAbility?.let { player.canPlayLand(candidate.card, false, it) } == true ||
                candidate.mdfcLandAbility?.canPlay() == true
        }

    companion object {
        fun query(
            game: Game,
            player: Player,
        ): PriorityActionCandidates {
            val cards =
                game
                    .getCardsIn(
                        listOf(
                            ZoneType.Hand,
                            ZoneType.Battlefield,
                            ZoneType.Graveyard,
                            ZoneType.Exile,
                            ZoneType.Command,
                        ),
                    ).distinctBy { it.id }
            val handIds = player.getZone(ZoneType.Hand).cards.mapTo(mutableSetOf()) { it.id }
            val battlefieldIds = player.getZone(ZoneType.Battlefield).cards.mapTo(mutableSetOf()) { it.id }
            return PriorityActionCandidates(
                cards.associate { card ->
                    val landAbility =
                        if (card.id in handIds && card.isLand) {
                            LandAbility(card, card.currentState).also { it.activatingPlayer = player }
                        } else {
                            null
                        }
                    val mdfcLandAbility =
                        if (card.id in handIds) {
                            buildMdfcBackLandAbility(card)?.also { it.activatingPlayer = player }
                        } else {
                            null
                        }
                    card.id to
                        CardCandidates(
                            card = card,
                            casts = getAllCastableAbilities(card, player),
                            activations = getNonManaActivatedAbilities(card, player),
                            manaAbilities =
                                if (card.id in battlefieldIds) getPlayableManaAbilities(card, player) else emptyList(),
                            landAbility = landAbility,
                            mdfcLandAbility = mdfcLandAbility,
                        )
                },
            )
        }
    }
}
