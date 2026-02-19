package forge.nexus.conformance

import forge.nexus.conformance.RecordingDecoder.AnnotationSummary
import forge.nexus.conformance.RecordingDecoder.DecodedMessage
import forge.nexus.conformance.RecordingDecoder.TurnInfoSummary
import forge.nexus.conformance.RecordingDecoder.ZoneSummary
import forge.nexus.conformance.SemanticTimeline.ActionPrompt
import forge.nexus.conformance.SemanticTimeline.GameOver
import forge.nexus.conformance.SemanticTimeline.GsIdStep
import forge.nexus.conformance.SemanticTimeline.PhaseChange
import forge.nexus.conformance.SemanticTimeline.TurnStart
import forge.nexus.conformance.SemanticTimeline.ZoneTransfer
import org.testng.Assert.*
import org.testng.annotations.Test

@Test(groups = ["unit"])
class SemanticTimelineTest {

    // --- helpers ---

    private fun msg(
        index: Int = 0,
        greType: String = "GameStateMessage",
        gsId: Int = 0,
        prevGsId: Int? = null,
        updateType: String? = null,
        annotations: List<AnnotationSummary> = emptyList(),
        zones: List<ZoneSummary> = emptyList(),
        turnInfo: TurnInfoSummary? = null,
        hasActionsAvailableReq: Boolean = false,
        hasIntermissionReq: Boolean = false,
        promptId: Int? = null,
        actions: List<RecordingDecoder.ActionSummary> = emptyList(),
    ) = DecodedMessage(
        index = index,
        file = "test.bin",
        greType = greType,
        gsId = gsId,
        prevGsId = prevGsId,
        updateType = updateType,
        annotations = annotations,
        zones = zones,
        turnInfo = turnInfo,
        hasActionsAvailableReq = hasActionsAvailableReq,
        hasIntermissionReq = hasIntermissionReq,
        promptId = promptId,
        actions = actions,
    )

    private fun turnInfo(
        phase: String = "Phase_Main1",
        step: String = "Step_None",
        turn: Int = 1,
        activePlayer: Int = 1,
        priorityPlayer: Int = 1,
    ) = TurnInfoSummary(
        phase = phase,
        step = step,
        turn = turn,
        activePlayer = activePlayer,
        priorityPlayer = priorityPlayer,
        decisionPlayer = activePlayer,
    )

    // --- ZoneTransfer ---

    @Test(description = "Extract ZoneTransfer from ObjectIdChanged + ZoneTransfer annotations")
    fun extractZoneTransfer() {
        val messages = listOf(
            msg(
                annotations = listOf(
                    AnnotationSummary(
                        id = 1,
                        types = listOf("ObjectIdChanged"),
                        affectorId = 100, // orig instanceId
                        affectedIds = listOf(200), // new instanceId
                    ),
                    AnnotationSummary(
                        id = 2,
                        types = listOf("ZoneTransfer"),
                        affectorId = 200, // new instanceId
                        details = mapOf("category" to "PlayLand"),
                    ),
                ),
                zones = listOf(
                    ZoneSummary(
                        zoneId = 10,
                        type = "ZoneType_Battlefield",
                        owner = 1,
                        visibility = "Visibility_Public",
                        objectIds = listOf(200),
                    ),
                ),
            ),
        )

        val events = SemanticTimeline.extract(messages)
        val transfers = events.filterIsInstance<ZoneTransfer>()

        assertEquals(transfers.size, 1, "Should extract exactly 1 ZoneTransfer")
        val zt = transfers[0]
        assertEquals(zt.origInstanceId, 100)
        assertEquals(zt.newInstanceId, 200)
        assertEquals(zt.category, "PlayLand")
        assertEquals(zt.destZoneType, "ZoneType_Battlefield")
    }

    // --- PhaseChange ---

    @Test(description = "Extract PhaseChange from PhaseOrStepModified annotation")
    fun extractPhaseChange() {
        val messages = listOf(
            msg(
                annotations = listOf(
                    AnnotationSummary(
                        id = 1,
                        types = listOf("PhaseOrStepModified"),
                    ),
                ),
                turnInfo = turnInfo(
                    phase = "Phase_Combat",
                    step = "Step_DeclareAttack",
                    activePlayer = 1,
                    priorityPlayer = 2,
                ),
            ),
        )

        val events = SemanticTimeline.extract(messages)
        val phases = events.filterIsInstance<PhaseChange>()

        assertEquals(phases.size, 1, "Should extract exactly 1 PhaseChange")
        val pc = phases[0]
        assertEquals(pc.phase, "Phase_Combat")
        assertEquals(pc.step, "Step_DeclareAttack")
        assertEquals(pc.activePlayer, 1)
        assertEquals(pc.priorityPlayer, 2)
    }

    // --- TurnStart ---

    @Test(description = "Extract TurnStart from NewTurnStarted annotation")
    fun extractTurnStart() {
        val messages = listOf(
            msg(
                annotations = listOf(
                    AnnotationSummary(
                        id = 1,
                        types = listOf("NewTurnStarted"),
                    ),
                ),
                turnInfo = turnInfo(turn = 3, activePlayer = 2),
            ),
        )

        val events = SemanticTimeline.extract(messages)
        val turns = events.filterIsInstance<TurnStart>()

        assertEquals(turns.size, 1, "Should extract exactly 1 TurnStart")
        assertEquals(turns[0].turnNumber, 3)
        assertEquals(turns[0].activePlayer, 2)
    }

    // --- ActionPrompt ---

    @Test(description = "Extract ActionPrompt from hasActionsAvailableReq")
    fun extractActionPrompt() {
        val messages = listOf(
            msg(
                hasActionsAvailableReq = true,
                promptId = 42,
                actions = listOf(
                    RecordingDecoder.ActionSummary(type = "ActionType_Cast", instanceId = 1),
                    RecordingDecoder.ActionSummary(type = "ActionType_Activate", instanceId = 2),
                ),
            ),
        )

        val events = SemanticTimeline.extract(messages)
        val prompts = events.filterIsInstance<ActionPrompt>()

        assertEquals(prompts.size, 1, "Should extract exactly 1 ActionPrompt")
        assertEquals(prompts[0].actionTypes, listOf("ActionType_Activate", "ActionType_Cast"))
        assertEquals(prompts[0].promptId, 42)
    }

    // --- GameOver ---

    @Test(description = "Extract GameOver from hasIntermissionReq")
    fun extractGameOver() {
        val messages = listOf(msg(hasIntermissionReq = true))

        val events = SemanticTimeline.extract(messages)
        val gameOvers = events.filterIsInstance<GameOver>()

        assertEquals(gameOvers.size, 1, "Should extract exactly 1 GameOver")
    }

    // --- Diff: matching ---

    @Test(description = "Diff: matching timelines produce empty divergences")
    fun diffMatchingTimelines() {
        val timeline = listOf(
            GsIdStep(gsId = 1, prevGsId = null, updateType = "Full", greType = "GameStateMessage"),
            GsIdStep(gsId = 2, prevGsId = 1, updateType = "Diff", greType = "GameStateMessage"),
            ZoneTransfer(origInstanceId = 100, newInstanceId = 200, category = "Draw", destZoneType = "ZoneType_Hand"),
        )

        val divergences = SemanticTimeline.diff(timeline, timeline)
        assertTrue(divergences.isEmpty(), "Matching timelines should produce no divergences: $divergences")
    }

    // --- Diff: zone transfer count ---

    @Test(description = "Diff: different zone transfer count produces divergence")
    fun diffDifferentZoneTransferCount() {
        val expected = listOf(
            ZoneTransfer(origInstanceId = 1, newInstanceId = 2, category = "Draw", destZoneType = "ZoneType_Hand"),
            ZoneTransfer(origInstanceId = 3, newInstanceId = 4, category = "Draw", destZoneType = "ZoneType_Hand"),
        )
        val actual = listOf(
            ZoneTransfer(origInstanceId = 1, newInstanceId = 2, category = "Draw", destZoneType = "ZoneType_Hand"),
        )

        val divergences = SemanticTimeline.diff(expected, actual)
        assertTrue(divergences.isNotEmpty(), "Should report divergences")
        assertTrue(
            divergences.any { "ZoneTransfer count" in it },
            "Should mention ZoneTransfer count: $divergences",
        )
    }

    // --- Diff: updateType sequence ---

    @Test(description = "Diff: different updateType sequence produces divergence")
    fun diffDifferentUpdateTypeSequence() {
        val expected = listOf(
            GsIdStep(gsId = 1, prevGsId = null, updateType = "Full", greType = "GameStateMessage"),
            GsIdStep(gsId = 2, prevGsId = 1, updateType = "Diff", greType = "GameStateMessage"),
        )
        val actual = listOf(
            GsIdStep(gsId = 1, prevGsId = null, updateType = "Full", greType = "GameStateMessage"),
            GsIdStep(gsId = 2, prevGsId = 1, updateType = "Full", greType = "GameStateMessage"),
        )

        val divergences = SemanticTimeline.diff(expected, actual)
        assertTrue(divergences.isNotEmpty(), "Should report divergences")
        assertTrue(
            divergences.any { "updateType sequence" in it },
            "Should mention updateType sequence: $divergences",
        )
    }
}
