package leyline.game

import forge.game.phase.PhaseType
import leyline.game.mapper.StopTypeMapping
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.BeforeClass
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.Stop
import wotc.mtgo.gre.external.messaging.Messages.StopType

/**
 * Tests for [StopTypeMapping] — pure mapping between Arena proto
 * [StopType] enums and Forge [PhaseType] enums.
 *
 * Needs conformance group: PhaseType.<clinit> requires Localizer
 * (loaded by initializeCardDatabase).
 */
@Test(groups = ["conformance"])
class StopTypeMappingTest {

    @BeforeClass(alwaysRun = true)
    fun init() {
        leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
    }

    @DataProvider(name = "allMappings")
    fun allMappings(): Array<Array<Any>> = arrayOf(
        arrayOf(StopType.UpkeepStep, PhaseType.UPKEEP),
        arrayOf(StopType.DrawStep, PhaseType.DRAW),
        arrayOf(StopType.PrecombatMainPhase, PhaseType.MAIN1),
        arrayOf(StopType.BeginCombatStep, PhaseType.COMBAT_BEGIN),
        arrayOf(StopType.DeclareAttackersStep, PhaseType.COMBAT_DECLARE_ATTACKERS),
        arrayOf(StopType.DeclareBlockersStep, PhaseType.COMBAT_DECLARE_BLOCKERS),
        arrayOf(StopType.CombatDamageStep, PhaseType.COMBAT_DAMAGE),
        arrayOf(StopType.EndCombatStep, PhaseType.COMBAT_END),
        arrayOf(StopType.PostcombatMainPhase, PhaseType.MAIN2),
        arrayOf(StopType.EndStep_ad1f, PhaseType.END_OF_TURN),
        arrayOf(StopType.FirstStrikeDamageStep, PhaseType.COMBAT_FIRST_STRIKE_DAMAGE),
    )

    @Test(dataProvider = "allMappings", description = "StopType -> PhaseType for all 11 types")
    fun stopTypeToPhaseType(stopType: StopType, expected: PhaseType) {
        assertEquals(StopTypeMapping.toPhaseType(stopType), expected)
    }

    @Test(dataProvider = "allMappings", description = "PhaseType -> StopType round-trip for all 11 types")
    fun phaseTypeToStopType(expected: StopType, phaseType: PhaseType) {
        assertEquals(StopTypeMapping.toStopType(phaseType), expected)
    }

    @Test(description = "None_ad1f maps to null (no corresponding phase)")
    fun noneStopTypeReturnsNull() {
        assertNull(StopTypeMapping.toPhaseType(StopType.None_ad1f))
    }

    @Test(description = "Phases without a StopType return null (UNTAP, CLEANUP)")
    fun unmappedPhasesReturnNull() {
        assertNull(StopTypeMapping.toStopType(PhaseType.UNTAP))
        assertNull(StopTypeMapping.toStopType(PhaseType.CLEANUP))
    }

    @Test(description = "All 11 StopType values (excluding None) have a mapping")
    fun allStopTypesCovered() {
        val mapped = StopType.values()
            .filter { it != StopType.None_ad1f && it != StopType.UNRECOGNIZED }
            .mapNotNull { StopTypeMapping.toPhaseType(it) }
        assertEquals(mapped.size, 11, "Expected all 11 non-None StopTypes to map")
    }

    // --- Stop list parsing ---

    @Test(description = "Parse a list of Stop protos into enabled PhaseType set")
    fun parseStopList() {
        val stops = listOf(
            stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set),
            stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set),
            stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
        )
        val enabled = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
        assertTrue(PhaseType.MAIN1 in enabled, "MAIN1 should be enabled")
        assertTrue(PhaseType.MAIN2 in enabled, "MAIN2 should be enabled")
        assertTrue(PhaseType.UPKEEP !in enabled, "UPKEEP should not be enabled (Clear)")
    }

    @Test(description = "Only stops matching the requested scope are included")
    fun scopeFiltering() {
        val stops = listOf(
            stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set),
            stop(StopType.DeclareAttackersStep, SettingScope.Opponents, SettingStatus.Set),
        )
        val teamStops = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
        assertTrue(PhaseType.MAIN1 in teamStops)
        assertTrue(PhaseType.COMBAT_DECLARE_ATTACKERS !in teamStops, "Opponents stop should not appear in Team scope")

        val opponentStops = StopTypeMapping.parseStops(stops, SettingScope.Opponents)
        assertTrue(PhaseType.COMBAT_DECLARE_ATTACKERS in opponentStops)
        assertTrue(PhaseType.MAIN1 !in opponentStops, "Team stop should not appear in Opponents scope")
    }

    @Test(description = "AnyPlayer scope matches both Team and Opponents queries")
    fun anyPlayerScopeMatchesBoth() {
        val stops = listOf(
            stop(StopType.DrawStep, SettingScope.AnyPlayer, SettingStatus.Set),
        )
        val teamStops = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
        assertTrue(PhaseType.DRAW in teamStops, "AnyPlayer should match Team query")

        val opponentStops = StopTypeMapping.parseStops(stops, SettingScope.Opponents)
        assertTrue(PhaseType.DRAW in opponentStops, "AnyPlayer should match Opponents query")
    }

    // --- Helper ---

    private fun stop(type: StopType, scope: SettingScope, status: SettingStatus): Stop =
        Stop.newBuilder()
            .setStopType(type)
            .setAppliesTo(scope)
            .setStatus(status)
            .build()
}
