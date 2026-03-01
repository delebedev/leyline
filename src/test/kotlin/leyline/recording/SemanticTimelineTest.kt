package leyline.recording

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.recording.RecordingDecoder.AnnotationSummary
import leyline.recording.RecordingDecoder.DecodedMessage
import leyline.recording.RecordingDecoder.TurnInfoSummary
import leyline.recording.RecordingDecoder.ZoneSummary
import leyline.recording.SemanticTimeline.ActionPrompt
import leyline.recording.SemanticTimeline.GameOver
import leyline.recording.SemanticTimeline.GsIdStep
import leyline.recording.SemanticTimeline.PhaseChange
import leyline.recording.SemanticTimeline.TurnStart
import leyline.recording.SemanticTimeline.ZoneTransfer

class SemanticTimelineTest :
    FunSpec({

        // --- helpers ---

        fun msg(
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

        fun turnInfo(
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

        test("Extract ZoneTransfer using details-based IDs (real proto shape)") {
            // Real protos: affectorId=0, IDs in details map
            val messages = listOf(
                msg(
                    annotations = listOf(
                        AnnotationSummary(
                            id = 1,
                            types = listOf("ObjectIdChanged"),
                            affectorId = 0,
                            affectedIds = listOf(100),
                            details = mapOf("orig_id" to 100, "new_id" to 220),
                        ),
                        AnnotationSummary(
                            id = 2,
                            types = listOf("ZoneTransfer"),
                            affectorId = 0,
                            affectedIds = listOf(220),
                            details = mapOf("zone_src" to 31, "zone_dest" to 28, "category" to "PlayLand"),
                        ),
                    ),
                    zones = listOf(
                        ZoneSummary(
                            zoneId = 28,
                            type = "Battlefield",
                            owner = 0,
                            visibility = "Public",
                            objectIds = listOf(220),
                        ),
                    ),
                ),
            )

            val events = SemanticTimeline.extract(messages)
            val transfers = events.filterIsInstance<ZoneTransfer>()

            transfers.size shouldBe 1
            val zt = transfers[0]
            zt.origInstanceId shouldBe 100
            zt.newInstanceId shouldBe 220
            zt.category shouldBe "PlayLand"
            zt.destZoneType shouldBe "Battlefield"
        }

        test("Extract ZoneTransfer using affectorId fallback (legacy shape)") {
            // Fallback: affectorId-based (no details map)
            val messages = listOf(
                msg(
                    annotations = listOf(
                        AnnotationSummary(
                            id = 1,
                            types = listOf("ObjectIdChanged"),
                            affectorId = 100,
                            affectedIds = listOf(200),
                        ),
                        AnnotationSummary(
                            id = 2,
                            types = listOf("ZoneTransfer"),
                            affectorId = 0,
                            affectedIds = listOf(200),
                            details = mapOf("category" to "PlayLand"),
                        ),
                    ),
                    zones = listOf(
                        ZoneSummary(
                            zoneId = 10,
                            type = "Battlefield",
                            owner = 1,
                            visibility = "Public",
                            objectIds = listOf(200),
                        ),
                    ),
                ),
            )

            val events = SemanticTimeline.extract(messages)
            val transfers = events.filterIsInstance<ZoneTransfer>()

            transfers.size shouldBe 1
            val zt = transfers[0]
            zt.origInstanceId shouldBe 100
            zt.newInstanceId shouldBe 200
            zt.category shouldBe "PlayLand"
            zt.destZoneType shouldBe "Battlefield"
        }

        // --- PhaseChange ---

        test("Extract PhaseChange from PhaseOrStepModified annotation") {
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

            phases.size shouldBe 1
            val pc = phases[0]
            pc.phase shouldBe "Phase_Combat"
            pc.step shouldBe "Step_DeclareAttack"
            pc.activePlayer shouldBe 1
            pc.priorityPlayer shouldBe 2
        }

        // --- TurnStart ---

        test("Extract TurnStart from NewTurnStarted annotation") {
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

            turns.size shouldBe 1
            turns[0].turnNumber shouldBe 3
            turns[0].activePlayer shouldBe 2
        }

        // --- ActionPrompt ---

        test("Extract ActionPrompt from hasActionsAvailableReq") {
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

            prompts.size shouldBe 1
            prompts[0].actionTypes shouldBe listOf("ActionType_Activate", "ActionType_Cast")
            prompts[0].promptId shouldBe 42
        }

        // --- GameOver ---

        test("Extract GameOver from hasIntermissionReq") {
            val messages = listOf(msg(hasIntermissionReq = true))

            val events = SemanticTimeline.extract(messages)
            val gameOvers = events.filterIsInstance<GameOver>()

            gameOvers.size shouldBe 1
        }

        // --- Diff: matching ---

        test("Diff: matching timelines produce empty divergences") {
            val timeline = listOf(
                GsIdStep(gsId = 1, prevGsId = null, updateType = "Full", greType = "GameStateMessage"),
                GsIdStep(gsId = 2, prevGsId = 1, updateType = "Diff", greType = "GameStateMessage"),
                ZoneTransfer(origInstanceId = 100, newInstanceId = 200, category = "Draw", destZoneType = "ZoneType_Hand"),
            )

            val divergences = SemanticTimeline.diff(timeline, timeline)
            divergences.shouldBeEmpty()
        }

        // --- Diff: zone transfer count ---

        test("Diff: different zone transfer count produces divergence") {
            val expected = listOf(
                ZoneTransfer(origInstanceId = 1, newInstanceId = 2, category = "Draw", destZoneType = "ZoneType_Hand"),
                ZoneTransfer(origInstanceId = 3, newInstanceId = 4, category = "Draw", destZoneType = "ZoneType_Hand"),
            )
            val actual = listOf(
                ZoneTransfer(origInstanceId = 1, newInstanceId = 2, category = "Draw", destZoneType = "ZoneType_Hand"),
            )

            val divergences = SemanticTimeline.diff(expected, actual)
            divergences.shouldNotBeEmpty()
            divergences.shouldExist { "ZoneTransfer count" in it }
        }

        // --- Diff: updateType sequence ---

        test("Diff: different updateType sequence produces divergence") {
            val expected = listOf(
                GsIdStep(gsId = 1, prevGsId = null, updateType = "Full", greType = "GameStateMessage"),
                GsIdStep(gsId = 2, prevGsId = 1, updateType = "Diff", greType = "GameStateMessage"),
            )
            val actual = listOf(
                GsIdStep(gsId = 1, prevGsId = null, updateType = "Full", greType = "GameStateMessage"),
                GsIdStep(gsId = 2, prevGsId = 1, updateType = "Full", greType = "GameStateMessage"),
            )

            val divergences = SemanticTimeline.diff(expected, actual)
            divergences.shouldNotBeEmpty()
            divergences.shouldExist { "updateType sequence" in it }
        }
    })
