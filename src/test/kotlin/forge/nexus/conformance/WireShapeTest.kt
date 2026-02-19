package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Client wire protocol shape validation.
 *
 * These tests load structural fingerprints extracted from real client recordings
 * and assert on the exact message patterns the client uses for each game action.
 * They do NOT boot the Forge engine — they validate our understanding of what
 * the real server sends, which guides our BundleBuilder implementation.
 *
 * Source recordings live in src/test/resources/golden/arena-*.json.
 * Each file is a JSON array of [StructuralFingerprint] extracted from a real
 * client session capture.
 */
@Test(groups = ["recording"])
class WireShapeTest {

    private fun loadGolden(name: String): List<StructuralFingerprint> =
        GoldenSequence.fromResource("golden/arena-$name.json")

    // ===== Draw step =====

    @Test(description = "Draw step: 2 msgs — ZoneTransfer(Draw) diff + echo")
    fun drawStepShape() {
        val fps = loadGolden("draw-step")
        assertEquals(fps.size, 2, "Draw step should have 2 messages")

        assertEquals(fps[0].greMessageType, "GameStateMessage")
        assertEquals(fps[0].gsType, "Diff")
        assertEquals(fps[0].updateType, "SendHiFi")
        assertTrue(fps[0].annotationTypes.contains("ZoneTransfer"))
        assertTrue(fps[0].annotationTypes.contains("ObjectIdChanged"))
        assertTrue(fps[0].annotationTypes.contains("PhaseOrStepModified"))
        assertTrue(fps[0].annotationCategories.contains("Draw"))
        assertTrue(fps[0].fieldPresence.contains("zones"))
        assertTrue(fps[0].fieldPresence.contains("objects"))
        assertTrue(fps[0].fieldPresence.contains("annotations"))

        assertEquals(fps[1].greMessageType, "GameStateMessage")
        assertEquals(fps[1].gsType, "Diff")
        assertEquals(fps[1].updateType, "SendHiFi")
        assertTrue(fps[1].annotationTypes.isEmpty(), "Echo should have no annotations")
        assertTrue(fps[1].annotationCategories.isEmpty())
    }

    // ===== New turn =====

    @Test(description = "New turn: 2 msgs — NewTurnStarted + 4x PhaseOrStepModified diff + echo")
    fun newTurnShape() {
        val fps = loadGolden("new-turn")
        assertEquals(fps.size, 2, "New turn should have 2 messages")

        assertEquals(fps[0].greMessageType, "GameStateMessage")
        assertEquals(fps[0].gsType, "Diff")
        assertEquals(fps[0].updateType, "SendHiFi")
        assertTrue(fps[0].annotationTypes.contains("NewTurnStarted"))
        val phaseCount = fps[0].annotationTypes.count { it == "PhaseOrStepModified" }
        assertEquals(phaseCount, 4, "Should have 4 PhaseOrStepModified (untap→upkeep→draw→main1)")
        assertTrue(fps[0].fieldPresence.contains("players"), "Turn ownership changes")
        assertTrue(fps[0].fieldPresence.contains("annotations"))
        assertTrue(fps[0].annotationCategories.isEmpty(), "NewTurnStarted has no category")

        assertEquals(fps[1].greMessageType, "GameStateMessage")
        assertEquals(fps[1].gsType, "Diff")
        assertEquals(fps[1].updateType, "SendHiFi")
        assertTrue(fps[1].annotationTypes.isEmpty(), "Echo should have no annotations")
    }

    // ===== Game end =====

    @Test(description = "Game end: 3x empty GS Diff SendAndRecord + IntermissionReq")
    fun gameEndShape() {
        val fps = loadGolden("game-end")
        assertEquals(fps.size, 4, "Game end should have 4 messages")

        for (i in 0..2) {
            assertEquals(fps[i].greMessageType, "GameStateMessage")
            assertEquals(fps[i].gsType, "Diff")
            assertEquals(fps[i].updateType, "SendAndRecord")
            assertTrue(fps[i].annotationTypes.isEmpty())
            assertTrue(fps[i].annotationCategories.isEmpty())
        }

        assertTrue(fps[0].fieldPresence.contains("gameInfo"), "First flush has gameInfo")
        assertTrue(fps[0].fieldPresence.contains("players"), "First flush has players")
        assertTrue(fps[0].fieldPresence.contains("timers"), "First flush has timers")

        assertEquals(fps[3].greMessageType, "IntermissionReq")
        assertEquals(fps[3].gsType, null)
        assertEquals(fps[3].updateType, null)
    }

    // ===== Cast spell (player perspective) =====

    @Test(description = "Cast spell player: 2x GS SendAndRecord CastSpell + ActionsAvailableReq")
    fun castSpellPlayerShape() {
        val fps = loadGolden("cast-spell-player")
        assertEquals(fps.size, 3, "Cast spell (player) should have 3 messages")

        for (i in 0..1) {
            assertEquals(fps[i].greMessageType, "GameStateMessage")
            assertEquals(fps[i].gsType, "Diff")
            assertEquals(fps[i].updateType, "SendAndRecord")
            assertTrue(fps[i].annotationCategories.contains("CastSpell"))
            assertTrue(fps[i].fieldPresence.contains("zones"))
            assertTrue(fps[i].fieldPresence.contains("objects"))
            assertTrue(fps[i].fieldPresence.contains("actions"))
            assertTrue(fps[i].fieldPresence.contains("annotations"))
            assertTrue(fps[i].annotationTypes.contains("ZoneTransfer"))
            assertTrue(fps[i].annotationTypes.contains("ObjectIdChanged"))
            assertTrue(fps[i].annotationTypes.contains("ManaPaid"))
        }

        assertEquals(fps[2].greMessageType, "ActionsAvailableReq")
        assertTrue(fps[2].hasPrompt)
        assertEquals(fps[2].promptId, 2)
    }

    // ===== Cast creature (AI perspective) =====

    @Test(description = "Cast creature AI: 4 msgs — cast diff+echo, resolve diff+echo (SendHiFi)")
    fun castCreatureAiShape() {
        val fps = loadGolden("cast-creature")
        assertEquals(fps.size, 4, "Client cast-creature should have 4 messages")

        // Cast diff
        assertEquals(fps[0].greMessageType, "GameStateMessage")
        assertEquals(fps[0].updateType, "SendHiFi")
        assertTrue(fps[0].annotationCategories.contains("CastSpell"))

        // Cast echo
        assertEquals(fps[1].greMessageType, "GameStateMessage")
        assertEquals(fps[1].updateType, "SendHiFi")
        assertTrue(fps[1].annotationTypes.isEmpty())

        // Resolve diff
        assertEquals(fps[2].greMessageType, "GameStateMessage")
        assertEquals(fps[2].updateType, "SendHiFi")
        assertTrue(fps[2].annotationCategories.contains("Resolve"))

        // Resolve echo
        assertEquals(fps[3].greMessageType, "GameStateMessage")
        assertEquals(fps[3].updateType, "SendHiFi")
        assertTrue(fps[3].annotationTypes.isEmpty())
    }

    // ===== Combat simple =====

    @Test(description = "Combat simple: 11 msgs from DeclareAttackersReq through post-combat")
    fun combatSimpleShape() {
        val fps = loadGolden("combat-simple")
        assertEquals(fps.size, 11, "Combat simple should have 11 messages")

        // [0] DeclareAttackersReq promptId=6
        assertEquals(fps[0].greMessageType, "DeclareAttackersReq")
        assertTrue(fps[0].hasPrompt)
        assertEquals(fps[0].promptId, 6)

        // [1] CastSpell aiActionDiff
        assertEquals(fps[1].greMessageType, "GameStateMessage")
        assertEquals(fps[1].gsType, "Diff")
        assertEquals(fps[1].updateType, "SendHiFi")
        assertTrue(fps[1].annotationCategories.contains("CastSpell"))
        assertTrue(fps[1].annotationTypes.contains("ZoneTransfer"))
        assertTrue(fps[1].annotationTypes.contains("ManaPaid"))

        // [2] CastSpell echo
        assertEquals(fps[2].updateType, "SendHiFi")
        assertTrue(fps[2].annotationTypes.isEmpty())
        assertTrue(fps[2].fieldPresence.contains("turnInfo"))
        assertTrue(fps[2].fieldPresence.contains("actions"))

        // [3] Resolve diff
        assertEquals(fps[3].updateType, "SendHiFi")
        assertTrue(fps[3].annotationCategories.contains("Resolve"))
        assertTrue(fps[3].annotationTypes.contains("ResolutionStart"))
        assertTrue(fps[3].annotationTypes.contains("ResolutionComplete"))

        // [4] Resolve echo
        assertEquals(fps[4].updateType, "SendHiFi")
        assertTrue(fps[4].annotationTypes.isEmpty())

        // [5-6] Phase transition pair (SendHiFi)
        assertTrue(fps[5].annotationTypes.contains("PhaseOrStepModified"))
        assertTrue(fps[6].annotationTypes.isEmpty())

        // [7] Phase transition SendAndRecord with timers
        assertEquals(fps[7].updateType, "SendAndRecord")
        assertTrue(fps[7].annotationTypes.contains("PhaseOrStepModified"))
        assertTrue(fps[7].fieldPresence.contains("timers"))

        // [8] SubmitAttackersResp promptId=6 (matches request)
        assertEquals(fps[8].greMessageType, "SubmitAttackersResp")
        assertTrue(fps[8].hasPrompt)
        assertEquals(fps[8].promptId, 6)
        assertEquals(fps[0].promptId, fps[8].promptId, "Req/Resp share promptId")

        // [9-10] Post-combat diffs
        for (i in 9..10) {
            assertEquals(fps[i].greMessageType, "GameStateMessage")
            assertEquals(fps[i].gsType, "Diff")
            assertEquals(fps[i].updateType, "SendHiFi")
        }
    }

    // ===== Combat damage =====

    @Test(description = "Combat damage: 14 msgs with full attack/block flow")
    fun combatDamageShape() {
        val fps = loadGolden("combat-damage")
        assertEquals(fps.size, 14, "Combat damage should have 14 messages")

        // [0] DeclareAttackersReq
        assertEquals(fps[0].greMessageType, "DeclareAttackersReq")
        assertEquals(fps[0].promptId, 6)

        // [1-3] Uimessage
        for (i in 1..3) {
            assertEquals(fps[i].greMessageType, "Uimessage")
        }

        // [4-5] SendAndRecord diffs with objects + timers
        for (i in 4..5) {
            assertEquals(fps[i].updateType, "SendAndRecord")
            assertTrue(fps[i].fieldPresence.contains("objects"))
            assertTrue(fps[i].fieldPresence.contains("timers"))
        }

        // [6] DeclareAttackersReq re-prompt
        assertEquals(fps[6].greMessageType, "DeclareAttackersReq")
        assertEquals(fps[6].promptId, 6)

        // [7] TappedUntappedPermanent (attack animation)
        assertEquals(fps[7].updateType, "SendHiFi")
        assertTrue(fps[7].annotationTypes.contains("TappedUntappedPermanent"))

        // [8] Echo
        assertTrue(fps[8].annotationTypes.isEmpty())

        // [9] Phase transition to blockers
        assertEquals(fps[9].updateType, "SendAndRecord")
        assertTrue(fps[9].annotationTypes.contains("PhaseOrStepModified"))

        // [10] DeclareBlockersReq promptId=7
        assertEquals(fps[10].greMessageType, "DeclareBlockersReq")
        assertEquals(fps[10].promptId, 7)

        // [11] SubmitAttackersResp promptId=6
        assertEquals(fps[11].greMessageType, "SubmitAttackersResp")
        assertEquals(fps[11].promptId, 6)
        assertTrue(
            fps[10].promptId!! < fps[11].promptId!! || fps[11].promptId!! < fps[10].promptId!!,
            "Blockers and attackers use different promptIds",
        )

        // [12] Post-blocker TappedUntappedPermanent
        assertTrue(fps[12].annotationTypes.contains("TappedUntappedPermanent"))

        // [13] Post-blocker echo
        assertTrue(fps[13].annotationTypes.isEmpty())
    }

    // ===== Targeted spell =====

    @Test(description = "Targeted spell: 18-message interaction with SelectTargetsReq, SelectNreq, QueuedGSM")
    fun targetedSpellShape() {
        val fps = loadGolden("targeted-spell")
        assertEquals(fps.size, 18, "Targeted spell should have 18 messages")

        // [0] GS Diff Send — CastSpell + PlayerSelectingTargets
        assertEquals(fps[0].updateType, "Send")
        assertTrue(fps[0].annotationCategories.contains("CastSpell"))
        assertTrue(fps[0].annotationTypes.contains("PlayerSelectingTargets"))

        // [1] SelectTargetsReq promptId=10
        assertEquals(fps[1].greMessageType, "SelectTargetsReq")
        assertEquals(fps[1].promptId, 10)

        // [8] SubmitTargetsResp
        assertEquals(fps[8].greMessageType, "SubmitTargetsResp")

        // [9] SelectNreq promptId=1243
        assertEquals(fps[9].greMessageType, "SelectNreq")
        assertEquals(fps[9].promptId, 1243)

        // [10-11] QueuedGameStateMessage pair
        for (i in 10..11) {
            assertEquals(fps[i].greMessageType, "QueuedGameStateMessage")
            assertEquals(fps[i].gsType, "Diff")
            assertEquals(fps[i].updateType, "Send")
        }
        assertTrue(fps[10].annotationCategories.contains("CastSpell"))
        assertTrue(fps[10].annotationTypes.contains("PlayerSelectingTargets"))
        assertTrue(fps[11].annotationTypes.isEmpty())

        // [7] and [14] Resolution phases
        for (i in listOf(7, 14)) {
            assertEquals(fps[i].updateType, "Send")
            assertTrue(fps[i].annotationTypes.contains("ResolutionStart"))
            assertTrue(fps[i].annotationTypes.contains("RevealedCardCreated"))
        }

        // [16] Discard + Resolve categories
        assertTrue(fps[16].annotationCategories.contains("Discard"))
        assertTrue(fps[16].annotationCategories.contains("Resolve"))

        // [17] Final echo
        assertTrue(fps[17].annotationTypes.isEmpty())
        assertTrue(fps[17].fieldPresence.contains("turnInfo"))
        assertTrue(fps[17].fieldPresence.contains("actions"))
    }

    // ===== Edictal =====

    @Test(description = "Edictal context: SendAndRecord GS → EdictalMessage → SendHiFi GS")
    fun edictalShape() {
        val fps = loadGolden("edictal-pass")
        assertEquals(fps.size, 3)
        assertEquals(fps[0].updateType, "SendAndRecord")
        assertEquals(fps[1].greMessageType, "EdictalMessage")
        assertEquals(fps[2].updateType, "SendHiFi")
    }

    // ===== Declare attackers =====

    @Test(description = "Declare attackers: GS Diff + DeclareAttackersReq promptId=6")
    fun declareAttackersShape() {
        val fps = loadGolden("declare-attackers")
        assertEquals(fps.size, 2)
        assertEquals(fps[0].greMessageType, "GameStateMessage")
        assertEquals(fps[1].greMessageType, "DeclareAttackersReq")
        assertEquals(fps[1].promptId, 6)
    }
}
