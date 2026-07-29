package leyline.game

import forge.game.Game
import forge.game.card.Card
import forge.game.combat.CombatUtil
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.state.GameBridge

/** Protocol-neutral combat legality materialized while the worker owns Forge. */
data class CombatDeclarationFacts(
    val attackers: List<AttackerFact> = emptyList(),
    val blockers: List<BlockerFact> = emptyList(),
) {
    data class AttackerFact(
        val cardId: ForgeCardId,
        val legalRecipients: List<DamageRecipientFact>,
        val hasEnlist: Boolean,
    )

    data class BlockerFact(
        val cardId: ForgeCardId,
        val legalAttackerIds: List<ForgeCardId>,
    )

    sealed interface DamageRecipientFact {
        data class PlayerSeat(
            val seatId: SeatId,
        ) : DamageRecipientFact

        data class Planeswalker(
            val cardId: ForgeCardId,
        ) : DamageRecipientFact
    }
}

internal object CombatDeclarationCapture {
    fun capture(
        game: Game,
        bridge: GameBridge,
        seatId: SeatId,
    ): CombatDeclarationFacts {
        val player = bridge.getPlayer(seatId) ?: return CombatDeclarationFacts()
        return CombatDeclarationFacts(
            attackers = captureAttackers(player, seatId),
            blockers = captureBlockers(game, player),
        )
    }

    private fun captureAttackers(
        player: Player,
        seatId: SeatId,
    ): List<CombatDeclarationFacts.AttackerFact> =
        player
            .getZone(ZoneType.Battlefield)
            .cards
            .filter { it.isCreature && CombatUtil.canAttack(it) }
            .mapNotNull { card ->
                val recipients =
                    CombatUtil
                        .getAllPossibleDefenders(player)
                        .filter { defender -> CombatUtil.canAttack(card, defender) }
                        .mapNotNull { defender ->
                            when (defender) {
                                is Player -> CombatDeclarationFacts.DamageRecipientFact.PlayerSeat(seatId.opponent)
                                is Card ->
                                    defender
                                        .takeIf { it.isPlaneswalker }
                                        ?.let { CombatDeclarationFacts.DamageRecipientFact.Planeswalker(ForgeCardId(it.id)) }
                                else -> null
                            }
                        }
                recipients
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        CombatDeclarationFacts.AttackerFact(
                            cardId = ForgeCardId(card.id),
                            legalRecipients = it,
                            hasEnlist = card.hasKeyword("Enlist"),
                        )
                    }
            }

    private fun captureBlockers(
        game: Game,
        player: Player,
    ): List<CombatDeclarationFacts.BlockerFact> {
        val combat = game.phaseHandler.combat ?: return emptyList()
        return player
            .getZone(ZoneType.Battlefield)
            .cards
            .filter { it.isCreature && CombatUtil.canBlock(it, combat) }
            .mapNotNull { blocker ->
                combat.attackers
                    .filter { attacker -> CombatUtil.canBlock(attacker, blocker) }
                    .map { attacker -> ForgeCardId(attacker.id) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { CombatDeclarationFacts.BlockerFact(ForgeCardId(blocker.id), it) }
            }
    }
}
