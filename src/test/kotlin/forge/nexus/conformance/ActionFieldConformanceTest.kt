package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.StateMapper
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Action field conformance: verifies detailed proto fields on actions
 * match invariants observed in real Arena goldens.
 *
 * Golden-derived invariants:
 * - Cast: shouldStop=true, abilityGrpId>0, manaCost non-empty, autoTapSolution present
 * - Play: shouldStop=true, no abilityGrpId, no manaCost
 * - ActivateMana: shouldStop absent (false), has instanceId + grpId
 * - Pass: no fields besides actionType
 * - Activate: has instanceId + grpId, abilityGrpId when card data available
 */
@Test(groups = ["integration", "conformance"])
class ActionFieldConformanceTest : ConformanceTestBase() {

    @Test(description = "After playing a land: ActivateMana present for untapped mana source")
    fun afterPlayLandHasActivateMana() {
        val (b, game, _) = startGameAtMain1()
        playLand(b) ?: return

        val actions = StateMapper.buildActions(game, 1, b)
        val manaActions = actions.actionsList.filter { it.actionType == ActionType.ActivateMana }
        assertTrue(manaActions.isNotEmpty(), "Should have ActivateMana after playing a land")

        for (a in manaActions) {
            assertNotEquals(a.instanceId, 0, "ActivateMana should have instanceId")
            assertNotEquals(a.grpId, 0, "ActivateMana should have grpId")
            assertFalse(a.shouldStop, "ActivateMana should NOT have shouldStop=true")
        }
    }

    @Test(description = "Cast actions: shouldStop=true, abilityGrpId, manaCost, autoTapSolution")
    fun castActionFields() {
        val (b, game, _) = startGameAtMain1()

        // Play a land first to enable casting (need mana)
        playLand(b) ?: return

        val actions = StateMapper.buildActions(game, 1, b)
        val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
        assertTrue(castActions.isNotEmpty(), "Should have Cast actions after playing a land")

        for (a in castActions) {
            assertTrue(a.shouldStop, "Cast should have shouldStop=true")
            assertNotEquals(a.instanceId, 0, "Cast should have instanceId")
            assertNotEquals(a.grpId, 0, "Cast should have grpId")
            assertNotEquals(a.abilityGrpId, 0, "Cast should have abilityGrpId (from CardDb)")
            assertTrue(a.manaCostCount > 0, "Cast should have manaCost (non-free spells)")
            // autoTapSolution: present when untapped mana sources exist
            assertTrue(a.hasAutoTapSolution(), "Cast should have autoTapSolution when mana sources available")
            assertTrue(
                a.autoTapSolution.autoTapActionsCount > 0,
                "autoTapSolution should have at least one tap action",
            )
        }
    }

    @Test(description = "Play actions: shouldStop=true, no abilityGrpId, no manaCost")
    fun playActionFields() {
        val (b, game, _) = startGameAtMain1()
        val actions = StateMapper.buildActions(game, 1, b)

        val playActions = actions.actionsList.filter { it.actionType == ActionType.Play_add3 }
        // At seed 42 turn 1, there may or may not be a playable land depending on hand
        // Skip if no playable lands (can't play land if already played one, or none in hand)
        if (playActions.isEmpty()) return

        for (a in playActions) {
            assertTrue(a.shouldStop, "Play should have shouldStop=true")
            assertNotEquals(a.instanceId, 0, "Play should have instanceId")
            assertNotEquals(a.grpId, 0, "Play should have grpId")
            assertEquals(a.abilityGrpId, 0, "Play should NOT have abilityGrpId")
            assertEquals(a.manaCostCount, 0, "Play should NOT have manaCost")
        }
    }

    @Test(description = "Pass action: only actionType, no other fields")
    fun passActionFields() {
        val (b, game, _) = startGameAtMain1()
        val actions = StateMapper.buildActions(game, 1, b)

        val passActions = actions.actionsList.filter { it.actionType == ActionType.Pass }
        assertEquals(passActions.size, 1, "Should have exactly one Pass action")

        val pass = passActions[0]
        assertEquals(pass.instanceId, 0, "Pass should NOT have instanceId")
        assertEquals(pass.grpId, 0, "Pass should NOT have grpId")
        assertFalse(pass.shouldStop, "Pass should NOT have shouldStop")
    }

    @Test(description = "ActionsAvailableReq in postAction bundle matches direct buildActions")
    fun postActionBundleContainsActions() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)

        // Find the ActionsAvailableReq message
        val aarMsg = result.messages.find { it.hasActionsAvailableReq() }
        assertTrue(aarMsg != null, "postAction bundle should contain ActionsAvailableReq")

        val aar = aarMsg!!.actionsAvailableReq
        val typeSet = aar.actionsList.map { it.actionType.name }.toSet()
        assertTrue(typeSet.contains("Cast"), "ActionsAvailableReq should have Cast")
        assertTrue(typeSet.contains("Pass"), "ActionsAvailableReq should have Pass")
        assertTrue(typeSet.contains("ActivateMana"), "ActionsAvailableReq should have ActivateMana")
    }

    @Test(description = "AutoTapSolution maps mana sources to spell cost")
    fun autoTapSolutionMapsCorrectly() {
        val (b, game, _) = startGameAtMain1()
        playLand(b) ?: return

        val actions = StateMapper.buildActions(game, 1, b)
        val castActions = actions.actionsList.filter {
            it.actionType == ActionType.Cast && it.hasAutoTapSolution()
        }
        if (castActions.isEmpty()) return

        for (a in castActions) {
            val ats = a.autoTapSolution
            // Each autoTapAction should reference a valid instanceId
            for (tap in ats.autoTapActionsList) {
                assertNotEquals(tap.instanceId, 0, "AutoTapAction should reference a permanent")
            }
            // selectedManaColors count should match tap actions count
            assertEquals(
                ats.selectedManaColorsCount,
                ats.autoTapActionsCount,
                "selectedManaColors count should match autoTapActions count",
            )
            // manaPayments count should match tap actions count
            assertEquals(
                ats.manaPaymentsCount,
                ats.autoTapActionsCount,
                "manaPayments count should match autoTapActions count",
            )
        }
    }
}
