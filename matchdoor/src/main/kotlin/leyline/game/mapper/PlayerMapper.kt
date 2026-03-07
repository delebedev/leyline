package leyline.game.mapper

import forge.game.phase.PhaseType
import forge.game.player.Player
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Maps Forge [Player] state and phase info to Arena proto types.
 *
 * Pure functions — no bridge access, no side effects.
 * Extracted from [StateMapper] for independent testability.
 */
object PlayerMapper {

    fun buildPlayerInfo(player: Player?, seatId: Int): PlayerInfo {
        val builder = PlayerInfo.newBuilder()
            .setSystemSeatNumber(seatId)
            .setTeamId(seatId)
            .setStatus(PlayerStatus.InGame_a1c6)
            .setControllerSeatId(seatId)
            .setControllerType(ControllerType.Player_abfa)
            .addTimerIds(seatId) // real client: timerIds=[seatId]
        if (player != null) {
            builder.setLifeTotal(player.life)
                .setStartingLifeTotal(player.startingLife)
                .setMaxHandSize(player.maxHandSize)

            // Mana pool — disabled for now: client auto-subtracts floating mana
            // from displayed card costs, causing confusing 0-cost display.
            // TODO: re-enable once we understand the client's cost rendering rules
            // var manaId = 1
            // for (mana in player.manaPool) {
            //     val color = mapManaColor(mana.color)
            //     builder.addManaPool(ManaInfo.newBuilder().setManaId(manaId++).setColor(color))
            // }
        }
        return builder.build()
    }

    /** Inactivity timers — real client sends 2 per game state (one per seat). */
    fun buildTimers(): List<TimerInfo> = listOf(
        TimerInfo.newBuilder()
            .setTimerId(1)
            .setType(TimerType.Inactivity_a5e2)
            .setDurationSec(1020)
            .setBehavior(TimerBehavior.Timeout_a3cd)
            .setWarningThresholdSec(990)
            .build(),
        TimerInfo.newBuilder()
            .setTimerId(2)
            .setType(TimerType.Inactivity_a5e2)
            .setDurationSec(1020)
            .setRunning(true)
            .setBehavior(TimerBehavior.Timeout_a3cd)
            .setWarningThresholdSec(990)
            .build(),
    )

    fun mapPhase(phase: PhaseType?): Phase = when (phase) {
        null -> Phase.None_a549
        PhaseType.UNTAP, PhaseType.UPKEEP, PhaseType.DRAW -> Phase.Beginning_a549
        PhaseType.MAIN1 -> Phase.Main1_a549
        PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS,
        PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
        PhaseType.COMBAT_DAMAGE, PhaseType.COMBAT_END,
        -> Phase.Combat_a549
        PhaseType.MAIN2 -> Phase.Main2_a549
        PhaseType.END_OF_TURN, PhaseType.CLEANUP -> Phase.Ending_a549
    }

    fun mapStep(phase: PhaseType?): Step = when (phase) {
        null -> Step.None_a2cb
        PhaseType.UNTAP -> Step.Untap
        PhaseType.UPKEEP -> Step.Upkeep_a2cb
        PhaseType.DRAW -> Step.Draw_a2cb
        PhaseType.MAIN1, PhaseType.MAIN2 -> Step.None_a2cb
        PhaseType.COMBAT_BEGIN -> Step.BeginCombat_a2cb
        PhaseType.COMBAT_DECLARE_ATTACKERS -> Step.DeclareAttack_a2cb
        PhaseType.COMBAT_DECLARE_BLOCKERS -> Step.DeclareBlock_a2cb
        PhaseType.COMBAT_FIRST_STRIKE_DAMAGE -> Step.FirstStrikeDamage_a2cb
        PhaseType.COMBAT_DAMAGE -> Step.CombatDamage_a2cb
        PhaseType.COMBAT_END -> Step.EndCombat_a2cb
        PhaseType.END_OF_TURN -> Step.End_a2cb
        PhaseType.CLEANUP -> Step.Cleanup_a2cb
    }
}
