package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: combat phase.
 *
 * Validates the structural shape of two Arena combat recordings:
 *
 * arena-combat-simple (11 messages):
 *   Attacker prompt -> AI casts combat trick (aiActionDiff pair) ->
 *   resolve (aiActionDiff pair) -> phase transitions -> SubmitAttackersResp ->
 *   post-combat diffs.
 *
 * arena-combat-damage (14 messages):
 *   Attacker declaration with UI messages -> state updates -> re-prompt ->
 *   tap animations -> phase transition to blockers -> blocker prompt ->
 *   attacker confirmation -> post-blocker state.
 */
@Test(groups = ["integration", "conformance"])
class CombatConformanceTest : ConformanceTestBase() {

    @Test(description = "Combat simple shape: 11 msgs from attacker prompt through post-combat diffs")
    fun arenaCombatSimpleStructure() {
        val golden = loadGolden("arena-combat-simple")

        assertEquals(golden.size, 11, "Combat simple should have 11 messages")

        // [0] DeclareAttackersReq with promptId=6
        assertEquals(golden[0].greMessageType, "DeclareAttackersReq")
        assertTrue(golden[0].hasPrompt, "DeclareAttackersReq should have prompt")
        assertEquals(golden[0].promptId, 6, "DeclareAttackersReq promptId should be 6")

        // [1] CastSpell aiActionDiff: annotations include CastSpell category
        assertEquals(golden[1].greMessageType, "GameStateMessage")
        assertEquals(golden[1].gsType, "Diff")
        assertEquals(golden[1].updateType, "SendHiFi")
        assertTrue(golden[1].annotationCategories.contains("CastSpell"), "Should have CastSpell category")
        assertTrue(golden[1].annotationTypes.contains("ZoneTransfer"), "CastSpell should have ZoneTransfer")
        assertTrue(golden[1].annotationTypes.contains("ManaPaid"), "CastSpell should have ManaPaid")

        // [2] CastSpell echo: turnInfo + actions only, no annotations
        assertEquals(golden[2].greMessageType, "GameStateMessage")
        assertEquals(golden[2].gsType, "Diff")
        assertEquals(golden[2].updateType, "SendHiFi")
        assertTrue(golden[2].annotationTypes.isEmpty(), "CastSpell echo should have no annotations")
        assertTrue(golden[2].fieldPresence.contains("turnInfo"), "Echo should have turnInfo")
        assertTrue(golden[2].fieldPresence.contains("actions"), "Echo should have actions")

        // [3] Resolve aiActionDiff: Resolve category
        assertEquals(golden[3].greMessageType, "GameStateMessage")
        assertEquals(golden[3].gsType, "Diff")
        assertEquals(golden[3].updateType, "SendHiFi")
        assertTrue(golden[3].annotationCategories.contains("Resolve"), "Should have Resolve category")
        assertTrue(golden[3].annotationTypes.contains("ResolutionStart"), "Resolve should have ResolutionStart")
        assertTrue(golden[3].annotationTypes.contains("ResolutionComplete"), "Resolve should have ResolutionComplete")

        // [4] Resolve echo: turnInfo + actions only, no annotations
        assertEquals(golden[4].greMessageType, "GameStateMessage")
        assertEquals(golden[4].gsType, "Diff")
        assertEquals(golden[4].updateType, "SendHiFi")
        assertTrue(golden[4].annotationTypes.isEmpty(), "Resolve echo should have no annotations")

        // [5] Phase transition: PhaseOrStepModified, SendHiFi
        assertEquals(golden[5].greMessageType, "GameStateMessage")
        assertEquals(golden[5].gsType, "Diff")
        assertEquals(golden[5].updateType, "SendHiFi")
        assertTrue(
            golden[5].annotationTypes.contains("PhaseOrStepModified"),
            "Phase transition [5] should have PhaseOrStepModified",
        )

        // [6] Phase echo: no annotations
        assertEquals(golden[6].greMessageType, "GameStateMessage")
        assertEquals(golden[6].gsType, "Diff")
        assertEquals(golden[6].updateType, "SendHiFi")
        assertTrue(golden[6].annotationTypes.isEmpty(), "Phase echo [6] should have no annotations")

        // [7] Phase transition: SendAndRecord (durable), PhaseOrStepModified + timers
        assertEquals(golden[7].greMessageType, "GameStateMessage")
        assertEquals(golden[7].gsType, "Diff")
        assertEquals(golden[7].updateType, "SendAndRecord")
        assertTrue(
            golden[7].annotationTypes.contains("PhaseOrStepModified"),
            "Phase transition [7] should have PhaseOrStepModified",
        )
        assertTrue(golden[7].fieldPresence.contains("timers"), "SendAndRecord phase should have timers")

        // [8] SubmitAttackersResp with promptId=6
        assertEquals(golden[8].greMessageType, "SubmitAttackersResp")
        assertTrue(golden[8].hasPrompt, "SubmitAttackersResp should have prompt")
        assertEquals(golden[8].promptId, 6, "SubmitAttackersResp promptId should be 6")

        // [9-10] Post-combat diffs
        for (i in 9..10) {
            assertEquals(golden[i].greMessageType, "GameStateMessage", "Post-combat [$i]: GameStateMessage")
            assertEquals(golden[i].gsType, "Diff", "Post-combat [$i]: Diff")
            assertEquals(golden[i].updateType, "SendHiFi", "Post-combat [$i]: SendHiFi")
        }
    }

    @Test(description = "Combat damage shape: 14 msgs with full attack/block flow")
    fun arenaCombatDamageStructure() {
        val golden = loadGolden("arena-combat-damage")

        assertEquals(golden.size, 14, "Combat damage should have 14 messages")

        // [0] DeclareAttackersReq with promptId=6
        assertEquals(golden[0].greMessageType, "DeclareAttackersReq")
        assertTrue(golden[0].hasPrompt, "DeclareAttackersReq should have prompt")
        assertEquals(golden[0].promptId, 6, "DeclareAttackersReq promptId should be 6")

        // [1-3] Uimessage messages
        for (i in 1..3) {
            assertEquals(golden[i].greMessageType, "Uimessage", "Message [$i] should be Uimessage")
        }

        // [4-5] SendAndRecord state diffs with objects + timers
        for (i in 4..5) {
            assertEquals(golden[i].greMessageType, "GameStateMessage", "State update [$i]: GameStateMessage")
            assertEquals(golden[i].gsType, "Diff", "State update [$i]: Diff")
            assertEquals(golden[i].updateType, "SendAndRecord", "State update [$i]: SendAndRecord")
            assertTrue(golden[i].fieldPresence.contains("objects"), "State update [$i] should have objects")
            assertTrue(golden[i].fieldPresence.contains("timers"), "State update [$i] should have timers")
        }

        // [6] DeclareAttackersReq re-prompt with promptId=6
        assertEquals(golden[6].greMessageType, "DeclareAttackersReq")
        assertTrue(golden[6].hasPrompt, "Re-prompt should have prompt")
        assertEquals(golden[6].promptId, 6, "Re-prompt promptId should be 6")

        // [7] TappedUntappedPermanent annotation (attack animation)
        assertEquals(golden[7].greMessageType, "GameStateMessage")
        assertEquals(golden[7].gsType, "Diff")
        assertEquals(golden[7].updateType, "SendHiFi")
        assertTrue(
            golden[7].annotationTypes.contains("TappedUntappedPermanent"),
            "Tap animation [7] should have TappedUntappedPermanent",
        )

        // [8] Echo: turnInfo + actions only
        assertEquals(golden[8].greMessageType, "GameStateMessage")
        assertEquals(golden[8].gsType, "Diff")
        assertEquals(golden[8].updateType, "SendHiFi")
        assertTrue(golden[8].annotationTypes.isEmpty(), "Echo [8] should have no annotations")

        // [9] Phase transition: SendAndRecord + PhaseOrStepModified
        assertEquals(golden[9].greMessageType, "GameStateMessage")
        assertEquals(golden[9].gsType, "Diff")
        assertEquals(golden[9].updateType, "SendAndRecord")
        assertTrue(
            golden[9].annotationTypes.contains("PhaseOrStepModified"),
            "Phase transition [9] should have PhaseOrStepModified",
        )

        // [10] DeclareBlockersReq with promptId=7
        assertEquals(golden[10].greMessageType, "DeclareBlockersReq")
        assertTrue(golden[10].hasPrompt, "DeclareBlockersReq should have prompt")
        assertEquals(golden[10].promptId, 7, "DeclareBlockersReq promptId should be 7")

        // [11] SubmitAttackersResp with promptId=6
        assertEquals(golden[11].greMessageType, "SubmitAttackersResp")
        assertTrue(golden[11].hasPrompt, "SubmitAttackersResp should have prompt")
        assertEquals(golden[11].promptId, 6, "SubmitAttackersResp promptId should be 6")

        // [12] Post-blocker TappedUntappedPermanent
        assertEquals(golden[12].greMessageType, "GameStateMessage")
        assertEquals(golden[12].gsType, "Diff")
        assertEquals(golden[12].updateType, "SendHiFi")
        assertTrue(
            golden[12].annotationTypes.contains("TappedUntappedPermanent"),
            "Post-blocker [12] should have TappedUntappedPermanent",
        )

        // [13] Post-blocker echo
        assertEquals(golden[13].greMessageType, "GameStateMessage")
        assertEquals(golden[13].gsType, "Diff")
        assertEquals(golden[13].updateType, "SendHiFi")
        assertTrue(golden[13].annotationTypes.isEmpty(), "Post-blocker echo [13] should have no annotations")
    }

    @Test(description = "Combat simple prompt IDs: DeclareAttackersReq and SubmitAttackersResp share promptId=6")
    fun combatSimpleHasExpectedPromptIds() {
        val golden = loadGolden("arena-combat-simple")

        // DeclareAttackersReq at [0]
        val attackReq = golden[0]
        assertEquals(attackReq.greMessageType, "DeclareAttackersReq")
        assertEquals(attackReq.promptId, 6, "DeclareAttackersReq promptId")

        // SubmitAttackersResp at [8]
        val attackResp = golden[8]
        assertEquals(attackResp.greMessageType, "SubmitAttackersResp")
        assertEquals(attackResp.promptId, 6, "SubmitAttackersResp promptId")

        // Both share the same promptId (request/response pair)
        assertEquals(attackReq.promptId, attackResp.promptId, "Req/Resp should share promptId")
    }

    @Test(description = "Combat damage includes DeclareBlockersReq with promptId=7")
    fun combatDamageHasDeclareBlockers() {
        val golden = loadGolden("arena-combat-damage")

        // Find DeclareBlockersReq
        val blockersReq = golden.first { it.greMessageType == "DeclareBlockersReq" }
        assertEquals(blockersReq.promptId, 7, "DeclareBlockersReq promptId should be 7")
        assertTrue(blockersReq.hasPrompt, "DeclareBlockersReq should have prompt")

        // It appears after the phase transition and before SubmitAttackersResp
        val blockersIdx = golden.indexOfFirst { it.greMessageType == "DeclareBlockersReq" }
        val submitIdx = golden.indexOfFirst { it.greMessageType == "SubmitAttackersResp" }
        assertEquals(blockersIdx, 10, "DeclareBlockersReq should be at index 10")
        assertTrue(blockersIdx < submitIdx, "DeclareBlockersReq should come before SubmitAttackersResp")
    }
}
