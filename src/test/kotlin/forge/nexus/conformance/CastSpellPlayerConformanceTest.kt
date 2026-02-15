package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: cast spell (player perspective).
 *
 * Arena sends 3 messages for a player-perspective cast spell:
 *   1. GameStateMessage (Diff, SendAndRecord, CastSpell category — rich annotations)
 *   2. GameStateMessage (Diff, SendAndRecord, CastSpell category — rich annotations)
 *   3. ActionsAvailableReq (promptId=2, Cast/FloatMana/Pass/ActivateMana)
 *
 * This is the postAction pattern (diff + ActionsAvailableReq), NOT the aiActionDiff pattern.
 * Uses SendAndRecord (not SendHiFi like AI perspective), and ends with ActionsAvailableReq
 * giving the player next actions.
 */
@Test(groups = ["integration", "conformance"])
class CastSpellPlayerConformanceTest : ConformanceTestBase() {

    @Test(description = "Cast spell player: 3 messages, both GS are SendAndRecord with CastSpell category")
    fun arenaCastSpellPlayerStructure() {
        val golden = loadGolden("arena-cast-spell-player")

        assertEquals(golden.size, 3, "Cast spell (player) should have 3 messages")

        // Both GameStateMessage diffs are SendAndRecord with CastSpell category
        for (i in 0..1) {
            assertEquals(golden[i].greMessageType, "GameStateMessage", "Message $i: GameStateMessage")
            assertEquals(golden[i].gsType, "Diff", "Message $i: Diff")
            assertEquals(golden[i].updateType, "SendAndRecord", "Message $i: SendAndRecord")
            assertTrue(
                golden[i].annotationCategories.contains("CastSpell"),
                "Message $i: should have CastSpell category",
            )
            assertTrue(golden[i].fieldPresence.contains("zones"), "Message $i: should have zones")
            assertTrue(golden[i].fieldPresence.contains("objects"), "Message $i: should have objects")
            assertTrue(golden[i].fieldPresence.contains("actions"), "Message $i: should have actions")
            assertTrue(golden[i].fieldPresence.contains("annotations"), "Message $i: should have annotations")
        }

        // Last message is ActionsAvailableReq
        assertEquals(golden[2].greMessageType, "ActionsAvailableReq", "Message 2: ActionsAvailableReq")
    }

    @Test(description = "Cast spell player ends with ActionsAvailableReq promptId=2")
    fun castSpellPlayerEndsWithActionsAvailableReq() {
        val golden = loadGolden("arena-cast-spell-player")

        val last = golden[2]
        assertEquals(last.greMessageType, "ActionsAvailableReq")
        assertTrue(last.hasPrompt, "ActionsAvailableReq should have a prompt")
        assertEquals(last.promptId, 2, "ActionsAvailableReq promptId should be 2")
    }

    @Test(description = "Cast spell player: both GS messages have CastSpell annotations")
    fun castSpellPlayerHasCastSpellAnnotations() {
        val golden = loadGolden("arena-cast-spell-player")

        for (i in 0..1) {
            assertTrue(
                golden[i].annotationCategories.contains("CastSpell"),
                "Message $i: should have CastSpell category",
            )
            assertTrue(
                golden[i].annotationTypes.contains("ZoneTransfer"),
                "Message $i: should have ZoneTransfer annotation",
            )
            assertTrue(
                golden[i].annotationTypes.contains("ObjectIdChanged"),
                "Message $i: should have ObjectIdChanged annotation",
            )
            assertTrue(
                golden[i].annotationTypes.contains("ManaPaid"),
                "Message $i: should have ManaPaid annotation",
            )
        }
    }
}
