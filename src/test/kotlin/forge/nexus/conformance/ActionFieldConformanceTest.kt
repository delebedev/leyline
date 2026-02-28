package forge.nexus.conformance

import forge.nexus.game.mapper.ActionMapper
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Action field conformance: verifies detailed proto fields on actions
 * match invariants observed in real client goldens.
 *
 * Golden-derived invariants:
 * - Cast: shouldStop=true, abilityGrpId>0, manaCost non-empty, autoTapSolution present
 * - Play: shouldStop=true, no abilityGrpId, no manaCost
 * - ActivateMana: shouldStop absent (false), has instanceId + grpId
 * - Pass: no fields besides actionType
 * - Activate: has instanceId + grpId, abilityGrpId when card data available
 */
@Test(groups = ["conformance"])
class ActionFieldConformanceTest : ConformanceTestBase() {

    @Test(description = "After playing a land: ActivateMana present for untapped mana source")
    fun afterPlayLandHasActivateMana() {
        val (b, game, _) = startGameAtMain1()
        playLand(b) ?: error("playLand failed at seed 42")

        val actions = ActionMapper.buildActions(game, 1, b)
        val manaActions = actions.actionsList.filter { it.actionType == ActionType.ActivateMana }
        assertTrue(manaActions.isNotEmpty(), "Should have ActivateMana after playing a land")

        for (a in manaActions) {
            assertNotEquals(a.instanceId, 0, "ActivateMana should have instanceId")
            assertNotEquals(a.grpId, 0, "ActivateMana should have grpId")
            assertEquals(a.facetId, a.instanceId, "ActivateMana facetId should equal instanceId")
            assertFalse(a.shouldStop, "ActivateMana should NOT have shouldStop=true")
            assertTrue(a.isBatchable, "ActivateMana should be batchable")
            assertTrue(a.manaPaymentOptionsCount > 0, "ActivateMana should have manaPaymentOptions")
            assertTrue(a.manaSelectionsCount > 0, "ActivateMana should have manaSelections")
        }
    }

    @Test(description = "Cast actions: shouldStop=true, facetId, manaCost, autoTapSolution")
    fun castActionFields() {
        val (b, game, _) = startGameAtMain1()

        // Play a land first to enable casting (need mana)
        playLand(b) ?: error("playLand failed at seed 42")

        val actions = ActionMapper.buildActions(game, 1, b)
        val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
        assertTrue(castActions.isNotEmpty(), "Should have Cast actions after playing a land")

        for (a in castActions) {
            assertTrue(a.shouldStop, "Cast should have shouldStop=true")
            assertNotEquals(a.instanceId, 0, "Cast should have instanceId")
            assertNotEquals(a.grpId, 0, "Cast should have grpId")
            assertEquals(a.facetId, a.instanceId, "Cast facetId should equal instanceId")
            // abilityGrpId NOT set on Cast in ActionsAvailableReq (only in GSM embedded actions)
            assertEquals(a.abilityGrpId, 0, "Cast in AAR should NOT have abilityGrpId")
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
        val actions = ActionMapper.buildActions(game, 1, b)

        val playActions = actions.actionsList.filter { it.actionType == ActionType.Play_add3 }
        // At seed 42 turn 1, there may or may not be a playable land depending on hand
        // Skip if no playable lands (can't play land if already played one, or none in hand)
        if (playActions.isEmpty()) return

        for (a in playActions) {
            assertTrue(a.shouldStop, "Play should have shouldStop=true")
            assertNotEquals(a.instanceId, 0, "Play should have instanceId")
            assertNotEquals(a.grpId, 0, "Play should have grpId")
            assertEquals(a.facetId, a.instanceId, "Play facetId should equal instanceId")
            assertEquals(a.abilityGrpId, 0, "Play should NOT have abilityGrpId")
            assertEquals(a.manaCostCount, 0, "Play should NOT have manaCost")
        }
    }

    @Test(description = "Pass action: only actionType, no other fields")
    fun passActionFields() {
        val (b, game, _) = startGameAtMain1()
        val actions = ActionMapper.buildActions(game, 1, b)

        val passActions = actions.actionsList.filter { it.actionType == ActionType.Pass }
        assertEquals(passActions.size, 1, "Should have exactly one Pass action")

        val pass = passActions[0]
        assertEquals(pass.instanceId, 0, "Pass should NOT have instanceId")
        assertEquals(pass.grpId, 0, "Pass should NOT have grpId")
        assertFalse(pass.shouldStop, "Pass should NOT have shouldStop")
    }

    @Test(description = "Activate actions: shouldStop=true, has instanceId + grpId + abilityGrpId")
    fun activateActionFields() {
        val (b, game, _) = startGameAtMain1()
        // Play a land to get mana, then check for Activate actions
        playLand(b) ?: error("playLand failed at seed 42")

        val actions = ActionMapper.buildActions(game, 1, b)
        val activateActions = actions.actionsList.filter { it.actionType == ActionType.Activate_add3 }
        // Activate actions may not always be present (depends on hand contents)
        if (activateActions.isEmpty()) return

        for (a in activateActions) {
            assertTrue(a.shouldStop, "Activate should have shouldStop=true")
            assertNotEquals(a.instanceId, 0, "Activate should have instanceId")
            assertNotEquals(a.grpId, 0, "Activate should have grpId")
            assertEquals(a.facetId, a.instanceId, "Activate facetId should equal instanceId")
        }
    }

    @Test(description = "ActionsAvailableReq in postAction bundle matches direct buildActions")
    fun postActionBundleContainsActions() {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: error("playLand failed at seed 42")

        val result = postAction(game, b, counter)

        val aar = result.aarOrNull
        assertTrue(aar != null, "postAction bundle should contain ActionsAvailableReq")

        val typeSet = aar!!.actionsList.map { it.actionType.name }.toSet()
        assertTrue(typeSet.contains("Cast"), "ActionsAvailableReq should have Cast")
        assertTrue(typeSet.contains("Pass"), "ActionsAvailableReq should have Pass")
        assertTrue(typeSet.contains("ActivateMana"), "ActionsAvailableReq should have ActivateMana")
        assertTrue(typeSet.contains("FloatMana"), "ActionsAvailableReq should have FloatMana")

        // GSM should have pendingMessageCount=1 when AAR follows
        val gsm = result.gsmOrNull
        assertTrue(gsm != null, "postAction bundle should contain GameStateMessage")
        assertEquals(gsm!!.pendingMessageCount, 1, "GSM should have pendingMessageCount=1 when AAR follows")
    }

    @Test(description = "GSM embedded actions are stripped (no grpId/facetId/shouldStop/autoTapSolution)")
    fun gsmEmbeddedActionsStripped() {
        val (b, game, counter) = startGameAtMain1()
        playLand(b) ?: error("playLand failed at seed 42")

        val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM in post-action result")

        for (actionInfo in gsm.actionsList) {
            val a = actionInfo.action
            when (a.actionType) {
                ActionType.Cast -> {
                    assertNotEquals(a.instanceId, 0, "GSM Cast should have instanceId")
                    assertEquals(a.grpId, 0, "GSM Cast should NOT have grpId")
                    assertEquals(a.facetId, 0, "GSM Cast should NOT have facetId")
                    assertFalse(a.shouldStop, "GSM Cast should NOT have shouldStop")
                    assertFalse(a.hasAutoTapSolution(), "GSM Cast should NOT have autoTapSolution")
                }
                ActionType.ActivateMana -> {
                    assertNotEquals(a.instanceId, 0, "GSM ActivateMana should have instanceId")
                    assertEquals(a.grpId, 0, "GSM ActivateMana should NOT have grpId")
                    assertEquals(a.facetId, 0, "GSM ActivateMana should NOT have facetId")
                }
                ActionType.Pass, ActionType.FloatMana -> {
                    assertEquals(a.instanceId, 0, "GSM Pass/FloatMana should NOT have instanceId")
                }
                else -> {}
            }
        }
    }

    @Test(description = "AutoTapSolution maps mana sources to spell cost")
    fun autoTapSolutionMapsCorrectly() {
        val (b, game, _) = startGameAtMain1()
        playLand(b) ?: error("playLand failed at seed 42")

        val actions = ActionMapper.buildActions(game, 1, b)
        val castActions = actions.actionsList.filter {
            it.actionType == ActionType.Cast && it.hasAutoTapSolution()
        }
        if (castActions.isEmpty()) return

        for (a in castActions) {
            val ats = a.autoTapSolution
            // Each autoTapAction should reference a valid instanceId + have manaPaymentOption
            for (tap in ats.autoTapActionsList) {
                assertNotEquals(tap.instanceId, 0, "AutoTapAction should reference a permanent")
                assertTrue(tap.hasManaPaymentOption(), "AutoTapAction should have manaPaymentOption")
                val mana = tap.manaPaymentOption.manaList
                assertTrue(mana.isNotEmpty(), "manaPaymentOption should have at least one ManaInfo")
                for (m in mana) {
                    assertNotEquals(m.srcInstanceId, 0, "ManaInfo should reference source permanent")
                    assertNotEquals(m.color, ManaColor.None_afc9, "ManaInfo should have a color")
                    assertTrue(m.count > 0, "ManaInfo should have count > 0")
                }
            }
        }
    }
}
