package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: targeted spell sequence.
 *
 * Arena sends 18 messages for a targeted spell interaction:
 *   [0]  GS Diff Send: CastSpell + PlayerSelectingTargets + ObjectIdChanged + ZoneTransfer
 *   [1]  SelectTargetsReq (promptId=10)
 *   [2]  Uimessage
 *   [3]  GS Diff Send: actions only (re-prompt state)
 *   [4]  SelectTargetsReq (promptId=10)
 *   [5]  GS Diff SendHiFi: confirmed targeting (ManaPaid, PlayerSubmittedTargets, etc.)
 *   [6]  GS Diff SendHiFi: echo (turnInfo + actions)
 *   [7]  GS Diff Send: ResolutionStart + RevealedCardCreated x6
 *   [8]  SubmitTargetsResp
 *   [9]  SelectNreq (promptId=1243)
 *   [10] QueuedGameStateMessage Diff Send: CastSpell + PlayerSelectingTargets
 *   [11] QueuedGameStateMessage Diff Send: actions only
 *   [12] GS Diff SendHiFi: confirmed targeting (same shape as [5])
 *   [13] GS Diff SendHiFi: echo (turnInfo + actions)
 *   [14] GS Diff Send: ResolutionStart + RevealedCardCreated x6
 *   [15] Uimessage
 *   [16] GS Diff SendHiFi: resolution complete (Discard + Resolve categories)
 *   [17] GS Diff SendHiFi: echo (turnInfo + actions)
 */
@Test(groups = ["integration", "conformance"])
class TargetedSpellConformanceTest : ConformanceTestBase() {

    @Test(description = "Targeted spell: 18 messages total")
    fun arenaTargetedSpellStructure() {
        val golden = loadGolden("arena-targeted-spell")

        assertEquals(golden.size, 18, "Targeted spell should have 18 messages")
    }

    @Test(description = "Targeted spell starts with GS Send CastSpell + SelectTargetsReq")
    fun targetedSpellStartsWithSelectTargets() {
        val golden = loadGolden("arena-targeted-spell")

        // [0] GS Diff Send with CastSpell + PlayerSelectingTargets
        assertEquals(golden[0].greMessageType, "GameStateMessage")
        assertEquals(golden[0].gsType, "Diff")
        assertEquals(golden[0].updateType, "Send")
        assertTrue(
            golden[0].annotationCategories.contains("CastSpell"),
            "Message [0]: should have CastSpell category",
        )
        assertTrue(
            golden[0].annotationTypes.contains("PlayerSelectingTargets"),
            "Message [0]: should have PlayerSelectingTargets annotation",
        )

        // [1] SelectTargetsReq with promptId=10
        assertEquals(golden[1].greMessageType, "SelectTargetsReq")
        assertTrue(golden[1].hasPrompt, "SelectTargetsReq should have a prompt")
        assertEquals(golden[1].promptId, 10, "SelectTargetsReq promptId should be 10")
    }

    @Test(description = "Targeted spell has SubmitTargetsResp at [8]")
    fun targetedSpellHasSubmitTargetsResp() {
        val golden = loadGolden("arena-targeted-spell")

        assertEquals(golden[8].greMessageType, "SubmitTargetsResp")
        assertEquals(golden[8].hasPrompt, false, "SubmitTargetsResp should have no prompt")
    }

    @Test(description = "Targeted spell has SelectNreq at [9] with promptId=1243")
    fun targetedSpellHasSelectNreq() {
        val golden = loadGolden("arena-targeted-spell")

        assertEquals(golden[9].greMessageType, "SelectNreq")
        assertTrue(golden[9].hasPrompt, "SelectNreq should have a prompt")
        assertEquals(golden[9].promptId, 1243, "SelectNreq promptId should be 1243")
    }

    @Test(description = "Targeted spell has QueuedGameStateMessages at [10-11]")
    fun targetedSpellHasQueuedGameStateMessages() {
        val golden = loadGolden("arena-targeted-spell")

        for (i in 10..11) {
            assertEquals(
                golden[i].greMessageType,
                "QueuedGameStateMessage",
                "Message [$i]: should be QueuedGameStateMessage",
            )
            assertEquals(golden[i].gsType, "Diff", "Message [$i]: Diff")
            assertEquals(golden[i].updateType, "Send", "Message [$i]: Send")
        }

        // [10] has CastSpell category + annotations
        assertTrue(
            golden[10].annotationCategories.contains("CastSpell"),
            "Message [10]: should have CastSpell category",
        )
        assertTrue(
            golden[10].annotationTypes.contains("PlayerSelectingTargets"),
            "Message [10]: should have PlayerSelectingTargets",
        )

        // [11] is actions only
        assertTrue(golden[11].fieldPresence.contains("actions"), "Message [11]: should have actions")
        assertTrue(golden[11].annotationTypes.isEmpty(), "Message [11]: should have no annotations")
    }

    @Test(description = "Targeted spell resolution phases at [7] and [14]")
    fun targetedSpellResolutionPhase() {
        val golden = loadGolden("arena-targeted-spell")

        for (i in listOf(7, 14)) {
            assertEquals(golden[i].greMessageType, "GameStateMessage", "Message [$i]: GameStateMessage")
            assertEquals(golden[i].gsType, "Diff", "Message [$i]: Diff")
            assertEquals(golden[i].updateType, "Send", "Message [$i]: Send")
            assertTrue(
                golden[i].annotationTypes.contains("ResolutionStart"),
                "Message [$i]: should have ResolutionStart",
            )
            assertTrue(
                golden[i].annotationTypes.contains("RevealedCardCreated"),
                "Message [$i]: should have RevealedCardCreated",
            )
            assertTrue(golden[i].fieldPresence.contains("annotations"), "Message [$i]: should have annotations")
            assertTrue(golden[i].fieldPresence.contains("zones"), "Message [$i]: should have zones")
            assertTrue(golden[i].fieldPresence.contains("objects"), "Message [$i]: should have objects")
        }
    }

    @Test(description = "Targeted spell ends with Resolve category + echo")
    fun targetedSpellEndsWithResolveCategory() {
        val golden = loadGolden("arena-targeted-spell")

        // [16] has Discard + Resolve categories
        assertEquals(golden[16].greMessageType, "GameStateMessage")
        assertEquals(golden[16].gsType, "Diff")
        assertEquals(golden[16].updateType, "SendHiFi")
        assertTrue(
            golden[16].annotationCategories.contains("Discard"),
            "Message [16]: should have Discard category",
        )
        assertTrue(
            golden[16].annotationCategories.contains("Resolve"),
            "Message [16]: should have Resolve category",
        )

        // [17] is echo (turnInfo + actions, no annotations)
        assertEquals(golden[17].greMessageType, "GameStateMessage")
        assertEquals(golden[17].gsType, "Diff")
        assertEquals(golden[17].updateType, "SendHiFi")
        assertTrue(golden[17].annotationTypes.isEmpty(), "Message [17]: echo should have no annotations")
        assertTrue(golden[17].fieldPresence.contains("turnInfo"), "Message [17]: echo should have turnInfo")
        assertTrue(golden[17].fieldPresence.contains("actions"), "Message [17]: echo should have actions")
    }
}
