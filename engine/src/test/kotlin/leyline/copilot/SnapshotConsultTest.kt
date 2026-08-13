package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.Blocker
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.ModalOption
import wotc.mtgo.gre.external.messaging.Messages.ModalReq
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.PlayerInfo
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.Target
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

private fun decodeSingle(hex: String): ClientToGREMessage {
    val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return ClientToGREMessage.parseFrom(bytes)
}

/**
 * End-to-end consult: serialize a running game's GSM, hand it with the game's
 * own pending prompt to [SnapshotConsult], and verify the proposal comes back
 * in the SOURCE game's id space (instanceIds from the source GSM, not the
 * hydrated game's own allocations) with a position eval attached.
 */
@Suppress("MissingAssertSoftly")
class SnapshotConsultTest :
    SessionTest({

        test("consult proposes the lethal bolt in source-game ids with eval") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=3

                humanhand=Lightning Bolt
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // The harness seeds its initial Full GSM into the accumulator rather
            // than the message log; rebuild the same wire Full GSM here.
            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm
            val aar = allMessages.last { it.hasActionsAvailableReq() }

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = aar,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "cast"
            val boltGrpId = TestCardRegistry.repo.findGrpIdByName("Lightning Bolt").shouldNotBeNull()
            val card = result.proposal.card.shouldNotBeNull()
            card.grpId shouldBe boltGrpId

            // The load-bearing assertion: the proposal references the SOURCE
            // game's instanceId for the bolt, so the response is submittable
            // against the source game as-is.
            val sourceBoltIids =
                gsm.gameObjectsList.filter { it.grpId == boltGrpId }.map { it.instanceId }
            sourceBoltIids.shouldNotBeEmpty()
            (card.instanceId in sourceBoltIids) shouldBe true

            result.proposal.responses.shouldNotBeEmpty()
            result.eval.shouldNotBeNull()
            result.fidelity.grade shouldBe "high_fidelity"
            result.fidelity.features
                .first { it.feature == "marked_damage" }
                .status shouldBe "carried"
            result.fidelity.features
                .first { it.feature == "attachments" }
                .status shouldBe "carried"
            result.fidelity.features
                .first { it.feature == "mana_pool" }
                .status shouldBe "carried"
        }

        test("consult proposes a cast after the source game's land drop is spent") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Land Drop
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Raging Goblin;Mountain
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // Spend the land drop in the source game; the follow-up prompt
            // offers casts only. Hydration resets the drop, so the consult
            // must re-derive "drop spent" from the prompt — otherwise the AI
            // holds every spell waiting to play a land it can no longer play.
            harness.playLand()
            val aar = allMessages.last { it.hasActionsAvailableReq() }
            aar.actionsAvailableReq.actionsList.none {
                it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Play_add3
            } shouldBe
                true

            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = aar,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "cast"
            result.proposal.card
                .shouldNotBeNull()
                .name shouldBe "Raging Goblin"
        }

        test("seat two consult proposes a playable land in source-game ids and seating") {
            val mountainGrpId = TestCardRegistry.repo.findGrpIdByName("Mountain").shouldNotBeNull()
            val sourceIid = 9001
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setGameStateId(39)
                    .setTurnInfo(
                        TurnInfo
                            .newBuilder()
                            .setPhase(Phase.Main1_a549)
                            .setTurnNumber(1)
                            .setActivePlayer(2)
                            .setPriorityPlayer(2)
                            .setDecisionPlayer(2),
                    ).addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.P2_HAND)
                            .setType(ZoneType.Hand)
                            .setVisibility(Visibility.Private)
                            .setOwnerSeatId(2)
                            .addViewers(2)
                            .addObjectInstanceIds(sourceIid),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(sourceIid)
                            .setGrpId(mountainGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(ZoneIds.P2_HAND)
                            .setVisibility(Visibility.Private)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2),
                    ).build()
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.ActionsAvailableReq_695e)
                    .setMsgId(17)
                    .setGameStateId(39)
                    .addSystemSeatIds(2)
                    .setActionsAvailableReq(
                        ActionsAvailableReq
                            .newBuilder()
                            .addActions(
                                Action
                                    .newBuilder()
                                    .setActionType(ActionType.Play_add3)
                                    .setGrpId(mountainGrpId)
                                    .setInstanceId(sourceIid),
                            ).addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                    ).build()

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = prompt,
                    seat = 2,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "play_land"
            result.proposal.card
                .shouldNotBeNull()
                .instanceId shouldBe sourceIid
            val response = decodeSingle(result.proposal.responses.single())
            response.systemSeatId shouldBe 2
            response.performActionResp.actionsList.single().let { action ->
                action.actionType shouldBe ActionType.Play_add3
                action.instanceId shouldBe sourceIid
            }
        }

        test("fully committed blocker snapshot submits without reinterpreting the accepted declaration") {
            val attackerGrpId = TestCardRegistry.repo.findGrpIdByName("Raging Goblin").shouldNotBeNull()
            val attackerIds = (1001..1006).toList()
            val blockerIds = (2001..2003).toList()
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setGameStateId(345)
                    .setTurnInfo(
                        TurnInfo
                            .newBuilder()
                            .setPhase(Phase.Combat_a549)
                            .setStep(Step.DeclareBlock_a2cb)
                            .setTurnNumber(13)
                            .setActivePlayer(1)
                            .setPriorityPlayer(1)
                            .setDecisionPlayer(2),
                    ).addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(21))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(5))
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.BATTLEFIELD)
                            .setType(ZoneType.Battlefield)
                            .setVisibility(Visibility.Public)
                            .addAllObjectInstanceIds(attackerIds + blockerIds),
                    ).apply {
                        attackerIds.forEach { instanceId ->
                            addGameObjects(
                                GameObjectInfo
                                    .newBuilder()
                                    .setInstanceId(instanceId)
                                    .setGrpId(attackerGrpId)
                                    .setType(GameObjectType.Card)
                                    .setZoneId(ZoneIds.BATTLEFIELD)
                                    .setVisibility(Visibility.Public)
                                    .setOwnerSeatId(1)
                                    .setControllerSeatId(1)
                                    .setAttackState(AttackState.Attacking),
                            )
                        }
                        blockerIds.forEach { instanceId ->
                            addGameObjects(
                                GameObjectInfo
                                    .newBuilder()
                                    .setInstanceId(instanceId)
                                    .setGrpId(attackerGrpId)
                                    .setType(GameObjectType.Card)
                                    .setZoneId(ZoneIds.BATTLEFIELD)
                                    .setVisibility(Visibility.Public)
                                    .setOwnerSeatId(2)
                                    .setControllerSeatId(2),
                            )
                        }
                    }.build()
            val committed = blockerIds.zip(attackerIds.take(blockerIds.size))
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.DeclareBlockersReq_695e)
                    .setMsgId(526)
                    .setGameStateId(345)
                    .addSystemSeatIds(2)
                    .setDeclareBlockersReq(
                        DeclareBlockersReq.newBuilder().apply {
                            committed.forEach { (blocker, attacker) ->
                                addBlockers(
                                    Blocker
                                        .newBuilder()
                                        .setBlockerInstanceId(blocker)
                                        .addSelectedAttackerInstanceIds(attacker)
                                        .setMaxAttackers(1),
                                )
                            }
                        },
                    ).build()

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = prompt,
                    seat = 2,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "submit_blockers"
            result.proposal.responseIds shouldBe emptyList()
            decodeSingle(result.proposal.responses.single()).type shouldBe ClientMessageType.SubmitBlockersReq
        }

        test("consult targets the OPPONENT player for a player-burn spell") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Player Target
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Lava Axe
                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain
                ailibrary=Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // Cast in the source game up to the targeting prompt, then consult
            // the snapshot about that prompt. The hydrated game has no bound
            // targeting ability, so this exercises the required-target path
            // where both candidates are players — the pick must be the
            // opponent's face (seat 2), never our own.
            castSpellByName("Lava Axe").shouldBeTrue()
            val targetsReq = allMessages.last { it.hasSelectTargetsReq() }

            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = targetsReq,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "target"
            result.proposal.responseIds shouldBe listOf(2)
        }

        test("target consult reproduces the live decision byte-for-byte (rebuilt ability)") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Target Fidelity
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Raging Goblin;Centaur Courser
                ailibrary=Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            // Multiple legal targets (two enemy creatures + both faces): the
            // pre-rebuild fallback picked by list order, not by the AI's
            // judgement. The contract under test: the hydrated consult routes
            // through the same ability the live game consults, so the response
            // bytes are identical — whatever the AI's pick is.
            castSpellByName("Shock").shouldBeTrue()
            val targetsReq = allMessages.last { it.hasSelectTargetsReq() }

            val sourceBridge = harness.bridge
            val live = CopilotProposalService(sourceBridge, leyline.bridge.types.SeatId(1)).propose(targetsReq)
            live.responses.shouldNotBeEmpty()

            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm
            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = targetsReq,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.responses shouldBe live.responses
        }

        test("multi-group target consult advances one group per echoed prompt") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Grouped Targets
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Grizzly Bears;Walking Corpse
                humanlibrary=Forest
                aibattlefield=Raging Goblin;Centaur Courser
                ailibrary=Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            val ownIds = listOf(human.battlefield.iid("Grizzly Bears"), human.battlefield.iid("Walking Corpse"))
            val opponentIds = listOf(ai.battlefield.iid("Raging Goblin"), ai.battlefield.iid("Centaur Courser"))
            val sourceBridge = harness.bridge
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0),
                        0,
                        "consult",
                        sourceBridge,
                        viewingSeatId = 1,
                    ).gsm

            fun target(
                instanceId: Int,
                selected: Boolean,
            ): Target =
                Target
                    .newBuilder()
                    .setTargetInstanceId(instanceId)
                    .setLegalAction(if (selected) SelectAction.Unselect else SelectAction.Select_a1ad)
                    .build()

            fun group(
                targetIdx: Int,
                candidates: List<Int>,
                selectedIds: List<Int>,
            ): TargetSelection =
                TargetSelection
                    .newBuilder()
                    .setTargetIdx(targetIdx)
                    .setMinTargets(1)
                    .setMaxTargets(1)
                    .apply {
                        if (selectedIds.isEmpty()) {
                            candidates.forEach { addTargets(target(it, selected = false)) }
                        } else {
                            selectedIds.forEach { addTargets(target(it, selected = true)) }
                            setSelectedTargets(selectedIds.size)
                        }
                    }.build()

            fun prompt(
                msgId: Int,
                selectedOwn: List<Int> = emptyList(),
                selectedOpponent: List<Int> = emptyList(),
            ): GREToClientMessage =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SelectTargetsReq_695e)
                    .setMsgId(msgId)
                    .setGameStateId(gsm.gameStateId)
                    .addSystemSeatIds(1)
                    .setSelectTargetsReq(
                        SelectTargetsReq
                            .newBuilder()
                            .addTargets(group(1, ownIds, selectedOwn))
                            .addTargets(group(2, opponentIds, selectedOpponent)),
                    ).build()

            fun consult(prompt: GREToClientMessage): ClientToGREMessage =
                decodeSingle(
                    SnapshotConsult
                        .consult(gsm = gsm, prompt = prompt, seat = 1, cardRepository = TestCardRegistry.repo)
                        .proposal.responses
                        .single(),
                )

            val first = consult(prompt(msgId = 71))
            first.type shouldBe ClientMessageType.SelectTargetsResp_097b
            first.selectTargetsResp.target.targetIdx shouldBe 1
            val selectedOwn =
                first.selectTargetsResp.target.targetsList
                    .single()
                    .targetInstanceId
            ownIds shouldContain selectedOwn

            val second = consult(prompt(msgId = 72, selectedOwn = listOf(selectedOwn)))
            second.type shouldBe ClientMessageType.SelectTargetsResp_097b
            second.selectTargetsResp.target.targetIdx shouldBe 2
            val selectedOpponent =
                second.selectTargetsResp.target.targetsList
                    .single()
                    .targetInstanceId
            opponentIds shouldContain selectedOpponent

            consult(
                prompt(msgId = 73, selectedOwn = listOf(selectedOwn), selectedOpponent = listOf(selectedOpponent)),
            ).type shouldBe
                ClientMessageType.SubmitTargetsReq

            val repair = consult(prompt(msgId = 74, selectedOpponent = opponentIds))
            repair.type shouldBe ClientMessageType.SelectTargetsResp_097b
            repair.selectTargetsResp.target.targetIdx shouldBe 2
            repair.selectTargetsResp.target.targetsList
                .single()
                .legalAction shouldBe SelectAction.Unselect
        }

        test("modal consult retains the prompt ctoId in proposal and response") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Consult Modal Identity
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain
                ailibrary=Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            val sourceBridge = harness.bridge
            val snap = GsmSnapshot.capture(sourceBridge.getGame()!!, sourceBridge, "consult", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "consult", sourceBridge, viewingSeatId = 1)
                    .gsm
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.CastingTimeOptionsReq_695e)
                    .setMsgId(91)
                    .setGameStateId(gsm.gameStateId)
                    .addSystemSeatIds(1)
                    .setCastingTimeOptionsReq(
                        CastingTimeOptionsReq
                            .newBuilder()
                            .addCastingTimeOptionReq(
                                CastingTimeOptionReq
                                    .newBuilder()
                                    .setCtoId(3)
                                    .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                                    .setIsRequired(true)
                                    .setModalReq(
                                        ModalReq
                                            .newBuilder()
                                            .setMinSel(1)
                                            .setMaxSel(1)
                                            .addModalOptions(ModalOption.newBuilder().setGrpId(42_001)),
                                    ),
                            ),
                    ).build()

            val result =
                SnapshotConsult.consult(
                    gsm = gsm,
                    prompt = prompt,
                    seat = 1,
                    cardRepository = TestCardRegistry.repo,
                )

            result.proposal.intent shouldBe "modal"
            result.proposal.ctoId shouldBe 3
            result.proposal.modalGrpIds shouldBe listOf(42_001)
            val response = decodeSingle(result.proposal.responses.single())
            response.castingTimeOptionsResp.castingTimeOptionResp.ctoId shouldBe 3
            response.castingTimeOptionsResp.castingTimeOptionResp.chooseModalResp.grpIdsList shouldBe listOf(42_001)
        }
    })
