package forge.nexus.game

import forge.game.phase.PhaseType
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.Stop
import wotc.mtgo.gre.external.messaging.Messages.StopType

/**
 * Pure mapping between Arena proto [StopType] enums and Forge [PhaseType] enums.
 *
 * Also provides [parseStops] to extract a set of enabled phases from a
 * [SettingsMessage.stops] list, filtered by [SettingScope].
 *
 * See `docs/rosetta.md` Table 6 for the full phase mapping reference.
 */
object StopTypeMapping {

    private val stopToPhase: Map<StopType, PhaseType> = mapOf(
        StopType.UpkeepStep to PhaseType.UPKEEP,
        StopType.DrawStep to PhaseType.DRAW,
        StopType.PrecombatMainPhase to PhaseType.MAIN1,
        StopType.BeginCombatStep to PhaseType.COMBAT_BEGIN,
        StopType.DeclareAttackersStep to PhaseType.COMBAT_DECLARE_ATTACKERS,
        StopType.DeclareBlockersStep to PhaseType.COMBAT_DECLARE_BLOCKERS,
        StopType.CombatDamageStep to PhaseType.COMBAT_DAMAGE,
        StopType.EndCombatStep to PhaseType.COMBAT_END,
        StopType.PostcombatMainPhase to PhaseType.MAIN2,
        StopType.EndStep_ad1f to PhaseType.END_OF_TURN,
        StopType.FirstStrikeDamageStep to PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
    )

    private val phaseToStop: Map<PhaseType, StopType> =
        stopToPhase.entries.associate { (k, v) -> v to k }

    /** Map a proto [StopType] to a Forge [PhaseType]. Returns null for [StopType.None_ad1f]. */
    fun toPhaseType(stopType: StopType): PhaseType? = stopToPhase[stopType]

    /** Map a Forge [PhaseType] to a proto [StopType]. Returns null for unmapped phases (UNTAP, CLEANUP). */
    fun toStopType(phaseType: PhaseType): StopType? = phaseToStop[phaseType]

    /**
     * Parse a list of [Stop] protos into a set of enabled [PhaseType]s for the given [scope].
     *
     * Only stops with [SettingStatus.Set] and a matching scope are included.
     * [SettingScope.AnyPlayer] matches any requested scope.
     */
    fun parseStops(stops: List<Stop>, scope: SettingScope): Set<PhaseType> {
        return stops
            .filter { it.status == SettingStatus.Set }
            .filter { it.appliesTo == scope || it.appliesTo == SettingScope.AnyPlayer }
            .mapNotNull { toPhaseType(it.stopType) }
            .toSet()
    }
}
