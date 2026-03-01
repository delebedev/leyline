package forge.nexus.game

import forge.game.phase.PhaseType
import forge.nexus.bridge.PhaseStopProfile
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test

/**
 * Tests for [PhaseStopProfile] — stop set per player.
 *
 * Conformance group: PhaseType.<clinit> requires Localizer
 * (loaded by initializeCardDatabase).
 */
@Test(groups = ["conformance"])
class PhaseStopProfileTest {

    @BeforeClass(alwaysRun = true)
    fun init() {
        forge.nexus.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
    }

    @Test
    fun humanDefaultsHaveCorrectPhases() {
        val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
        val humanStops = profile.getEnabled(1)
        assertEquals(
            humanStops,
            setOf(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2),
            "Human defaults should be Main1, DeclareAttackers, DeclareBlockers, Main2",
        )
    }

    @Test
    fun aiDefaultsHaveCorrectPhases() {
        val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
        val aiStops = profile.getEnabled(2)
        assertEquals(
            aiStops,
            setOf(PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.END_OF_TURN),
            "AI defaults should be CombatBegin, DeclareAttackers, DeclareBlockers, EndOfTurn",
        )
    }

    @Test
    fun isEnabledReturnsCorrectly() {
        val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
        assertTrue(profile.isEnabled(1, PhaseType.MAIN1), "Human should stop at Main1")
        assertFalse(profile.isEnabled(1, PhaseType.UPKEEP), "Human should not stop at Upkeep")
        assertFalse(profile.isEnabled(1, PhaseType.DRAW), "Human should not stop at Draw")
        assertFalse(profile.isEnabled(1, PhaseType.COMBAT_DAMAGE), "Human should not stop at CombatDamage")
        assertFalse(profile.isEnabled(1, PhaseType.COMBAT_END), "Human should not stop at CombatEnd")
        assertFalse(profile.isEnabled(1, PhaseType.CLEANUP), "Human should not stop at Cleanup")
    }

    @Test
    fun setEnabledTogglesStop() {
        val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
        assertFalse(profile.isEnabled(1, PhaseType.UPKEEP))

        profile.setEnabled(1, PhaseType.UPKEEP, true)
        assertTrue(profile.isEnabled(1, PhaseType.UPKEEP), "Upkeep should be enabled after set")

        profile.setEnabled(1, PhaseType.UPKEEP, false)
        assertFalse(profile.isEnabled(1, PhaseType.UPKEEP), "Upkeep should be disabled after clear")
    }

    @Test
    fun setEnabledForNewPlayerCreatesEntry() {
        val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
        // Player 99 doesn't exist in defaults
        assertFalse(profile.isEnabled(99, PhaseType.MAIN1))

        profile.setEnabled(99, PhaseType.MAIN1, true)
        assertTrue(profile.isEnabled(99, PhaseType.MAIN1))
    }

    @Test
    fun emptyProfileHasNoStops() {
        val profile = PhaseStopProfile.empty()
        for (phase in PhaseStopProfile.CANONICAL_PHASES) {
            assertFalse(profile.isEnabled(1, phase), "Empty profile should have no stops for player 1 at $phase")
        }
    }

    @Test
    fun twoPlayerDefaultsBothGetHumanStops() {
        val profile = PhaseStopProfile.createTwoPlayerDefaults(player1Id = 1, player2Id = 2)
        val expected = setOf(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2)
        assertEquals(profile.getEnabled(1), expected)
        assertEquals(profile.getEnabled(2), expected)
    }
}
