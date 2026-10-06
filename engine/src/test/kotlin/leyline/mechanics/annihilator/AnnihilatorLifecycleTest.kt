package leyline.mechanics.annihilator

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.acceptance.AcceptancePaths
import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PromptCallStatus
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.testkit.MatchFlowHarness
import leyline.testkit.ProtocolContract
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.tooling.headless.HeadlessResponseMode
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.ParameterType

class AnnihilatorLifecycleTest :
    SessionTest({
        fun puzzle(permanents: String) =
            """
            ActivePlayer=AI
            ActivePhase=Main1
            HumanLife=40
            AILife=20
            humanbattlefield=$permanents
            humanlibrary=Plains;Plains;Plains
            aibattlefield=Emrakul, the Aeons Torn
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        test("Annihilator timeout sacrifices six default permanents and combat continues") {
            val h =
                MatchFlowHarness(
                    engineSettings =
                        EngineSettings(
                            aiSpeed = 0.0,
                            bridgeTimeoutMs = 5_000L,
                            promptFailsafeMs = 100L,
                            aiTurnWaitMs = 500L,
                            mulliganWaitMs = 500L,
                        ),
                    fullControl = true,
                    responseMode = HeadlessResponseMode.PolicyVisible,
                )
            try {
                h.connect(
                    puzzleResource = "data/puzzles/resolution-annihilator-choice.pzl",
                    aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
                )
                val firstTurn = h.turn()
                val before =
                    h.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .toList()
                val handlesByIid = before.associateBy { card -> h.run { human.battlefield.iid(card.name) } }
                h.passUntil { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
                val prompt = h.allMessages.single { it.hasSelectNReq() }
                val expected =
                    prompt.selectNReq.idsList
                        .take(6)
                        .map { handlesByIid.getValue(it) }
                GameLoopPoller.awaitCondition(timeoutMs = 20_000L) {
                    h.drainSink()
                    h.bridge.cutCoordinator.cardSelect
                        .current() == null &&
                        h.bridge
                            .promptBridge(SeatId(1))
                            .history
                            .any { it.outcome == PromptCallStatus.TIMEOUT } &&
                        h.allMessages.any { it.hasActionsAvailableReq() && it.msgId > prompt.msgId }
                }
                h.passThroughCombat(firstTurn)
                h
                    .passUntil {
                        val pending = bridge.actionBridge(SeatId(1)).getPending()
                        turn() > firstTurn &&
                            pending?.state?.kind == PendingActionKind.PRIORITY &&
                            pendingActionHorizonPublished(pending, 0)
                    }.shouldBeTrue()
                val actionHorizon = checkNotNull(h.bridge.actionBridge(SeatId(1)).getPending())
                h.awaitPendingActionHorizon(actionHorizon, 0)
                val continuationPrompt = h.allMessages.last { it.hasActionsAvailableReq() }
                val fallback =
                    h.bridge
                        .promptBridge(SeatId(1))
                        .history
                        .single { it.outcome == PromptCallStatus.TIMEOUT }
                val annotations = h.allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
                assertSoftly {
                    prompt.selectNReq.minSel shouldBe 6
                    prompt.selectNReq.maxSel shouldBe 6
                    prompt.selectNReq.idsCount shouldBe 8
                    fallback.result shouldBe (0..5).toList()
                    h.human
                        .getZone(ZoneType.Graveyard)
                        .cards
                        .toList() shouldContainExactlyInAnyOrder expected
                    h.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .toList() shouldContainExactlyInAnyOrder before.filter { it !in expected }
                    annotations.count {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.detailsList.any { detail -> detail.key == "category" && "Sacrifice" in detail.valueStringList }
                    } shouldBe
                        6
                    h.game().stackZone.size() shouldBe 0
                    h.human.life shouldBe 25
                    h.turn() shouldBe firstTurn + 1
                    h.allMessages.last { it.hasActionsAvailableReq() }.gameStateId shouldBe actionHorizon.promptGameStateId
                    h.allMessages.any { it.hasDeclareBlockersReq() }.shouldBeFalse()
                    h.bridge.cutCoordinator.cardSelect
                        .current()
                        .shouldBeNull()
                    h.bridge.responseAcceptance
                        .acceptedSnapshot()
                        .none { it.respId == prompt.msgId }
                        .shouldBeTrue()
                    h.bridge.cutCoordinator
                        .failure()
                        .shouldBeNull()
                }
                h.passPriority()
                h.bridge.responseAcceptance
                    .acceptedSnapshot()
                    .any { it.respId == continuationPrompt.msgId }
                    .shouldBeTrue()
                h.allMessages.count { it.type == GREMessageType.IllegalRequest } shouldBe 0
            } finally {
                h.shutdown()
            }
        }

        session(
            "human defender chooses six sacrifices before blockers and combat continues",
            puzzle = puzzle("Healer's Hawk;Plains;Island;Swamp;Mountain;Forest;Wastes;Wastes"),
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            passUntil { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val promptMessage = allMessages.last { it.hasSelectNReq() }
            val selection = promptMessage.selectNReq
            val annotationsBeforeChoice = allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
            val abilityIid = annotationsBeforeChoice.last { AnnotationType.AbilityInstanceCreated in it.typeList }.affectedIdsList.single()
            annotationsBeforeChoice.count { AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid } shouldBe 1
            annotationsBeforeChoice.count { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == abilityIid } shouldBe 0
            val blocker = human.battlefield.card("Healer's Hawk")
            val blockerIid = human.battlefield.iid("Healer's Hawk")
            val before = human.getZone(ZoneType.Battlefield).cards.toList()
            val selectedIds = selection.idsList.filter { it != blockerIid }.takeLast(6)
            val selectedForgeIds = selectedIds.map { bridge.getForgeCardId(InstanceId(it))!!.value }.toSet()
            val sacrificed = before.filter { it.id in selectedForgeIds }
            val retained = before.filter { it.id !in selectedForgeIds }
            assertSoftly {
                promptMessage.systemSeatIdsList shouldBe listOf(HUMAN_SEAT)
                selection.minSel shouldBe 6
                selection.maxSel shouldBe 6
                selection.idsCount shouldBe 8
                promptMessage.prompt.promptId shouldBe 180
                promptMessage.prompt.parametersList.map { it.parameterName } shouldBe listOf("CardId", "CardId")
                promptMessage.prompt.parametersList.map { it.type } shouldBe listOf(ParameterType.Number, ParameterType.Number)
                promptMessage.prompt.parametersList.map { it.numberValue } shouldBe listOf(ai.battlefield.iid("Emrakul, the Aeons Torn"), 6)
                selection.sourceId shouldBe abilityIid
                selection.prompt.promptId shouldBe 0
                selection.prompt.parametersList
                    .single()
                    .parameterName shouldBe "Parameter"
                selection.prompt.parametersList
                    .single()
                    .type shouldBe ParameterType.PromptId
                selection.prompt.parametersList
                    .single()
                    .promptId shouldBe 5
                promptMessage.allowCancel shouldBe AllowCancel.No_a526
                sacrificed.size shouldBe 6
                phase() shouldBe "COMBAT_DECLARE_ATTACKERS"
                allMessages.any { it.hasDeclareBlockersReq() }.shouldBeFalse()
                human.getZone(ZoneType.Graveyard).size() shouldBe 0
            }

            respondToSelectN(selectedIds)
            passUntil { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()
            val contract = ProtocolContract.load(AcceptancePaths.resolve("conformance/contracts/emrakul-sacrifice-choice.yaml"))
            contract.verify(allMessages)
            val completed =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .single { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == abilityIid }
            val started = annotationsBeforeChoice.single { AnnotationType.ResolutionStart in it.typeList && it.affectorId == abilityIid }
            val delayedStart =
                allMessages.map { message ->
                    if (!message.hasGameStateMessage()) {
                        message
                    } else {
                        val gsm = message.gameStateMessage
                        val rows = gsm.annotationsList.filter { it != started }.toMutableList()
                        if (completed in rows) rows.add(0, started)
                        message.toBuilder().setGameStateMessage(gsm.toBuilder().clearAnnotations().addAllAnnotations(rows)).build()
                    }
                }
            val prematureCompletion =
                allMessages.map { message ->
                    if (!message.hasGameStateMessage() || started !in message.gameStateMessage.annotationsList) {
                        message
                    } else {
                        val gsm = message.gameStateMessage
                        message.toBuilder().setGameStateMessage(gsm.toBuilder().addAnnotations(completed)).build()
                    }
                }
            val finalSacrifice =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .last {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.affectorId == abilityIid &&
                            it.detailsList.any { detail -> detail.key == "category" && "Sacrifice" in detail.valueStringList }
                    }
            val finalIdChange =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .single {
                        AnnotationType.ObjectIdChanged in it.typeList &&
                            it.detailsList.any { detail ->
                                detail.key == "new_id" &&
                                    finalSacrifice.affectedIdsList.single() in detail.valueInt32List
                            }
                    }
            val lateSacrifice =
                allMessages.map { message ->
                    if (!message.hasGameStateMessage()) {
                        message
                    } else {
                        val gsm = message.gameStateMessage
                        val rows = gsm.annotationsList.filter { it != finalIdChange && it != finalSacrifice }.toMutableList()
                        val completionIndex = rows.indexOf(completed)
                        if (completionIndex >= 0) rows.addAll(completionIndex + 1, listOf(finalIdChange, finalSacrifice))
                        message.toBuilder().setGameStateMessage(gsm.toBuilder().clearAnnotations().addAllAnnotations(rows)).build()
                    }
                }
            for (mutatedStream in listOf(lateSacrifice, delayedStart, prematureCompletion)) {
                shouldThrow<AssertionError> { contract.verify(mutatedStream) }
            }
            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.toList() shouldContainExactlyInAnyOrder sacrificed
                human.getZone(ZoneType.Battlefield).cards.toList() shouldContainExactlyInAnyOrder retained
                blocker.zone.zoneType shouldBe ZoneType.Battlefield
                allMessages
                    .last { it.hasDeclareBlockersReq() }
                    .declareBlockersReq.blockersList
                    .any { it.blockerInstanceId == blockerIid }
                    .shouldBeTrue()
            }
            declareBlockers(emptyMap())
            assertSoftly {
                passUntil { phase() == "MAIN2" }.shouldBeTrue()
                human.life shouldBe 25
                isGameOver().shouldBeFalse()
            }
        }

        session(
            "fewer than six permanents are all sacrificed without a choice and combat continues",
            puzzle = puzzle("Plains;Island;Swamp"),
            turns = 4,
            aiScript = listOf(ScriptedAction.Attack(listOf("Emrakul, the Aeons Torn"))),
            fullControl = true,
            responseMode = HeadlessResponseMode.PolicyVisible,
        ) {
            val permanents = human.getZone(ZoneType.Battlefield).cards.toList()
            assertSoftly {
                permanents.size shouldBe 3
                passUntil { phase() == "MAIN2" }.shouldBeTrue()
                human.getZone(ZoneType.Battlefield).size() shouldBe 0
                human.getZone(ZoneType.Graveyard).cards.toList() shouldContainExactlyInAnyOrder permanents
                allMessages.any { it.hasSelectNReq() }.shouldBeFalse()
                human.life shouldBe 25
                isGameOver().shouldBeFalse()
            }
        }
    })
