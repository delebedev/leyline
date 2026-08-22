package leyline.copilot

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.*
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

        session(
            "consult proposes the lethal bolt in source-game ids with eval",
            puzzle = CONSULT_PROPOSES_LETHAL_BOLT_PUZZLE,
        ) {
            // The harness seeds its initial Full GSM into the accumulator rather
            // than the message log; rebuild the same wire Full GSM here.
            val aar = allMessages.last { it.hasActionsAvailableReq() }
            val result = advise(aar, leyline.tooling.headless.HeadlessAdviceMode.Snapshot)

            result.proposal.intent shouldBe "cast"
            val boltGrpId = cardGrpId("Lightning Bolt").shouldNotBeNull()
            val card = result.proposal.card.shouldNotBeNull()
            card.grpId shouldBe boltGrpId

            // The load-bearing assertion: the proposal references the SOURCE
            // game's instanceId for the bolt, so the response is submittable
            // against the source game as-is.
            val sourceBoltIids =
                human.hand.cards
                    .filter { it.name == "Lightning Bolt" }
                    .map { it.id }
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

        session(
            "consult proposes a cast after the source game's land drop is spent",
            puzzle = CONSULT_PROPOSES_CAST_AFTER_PUZZLE,
        ) {
            // Spend the land drop in the source game; the follow-up prompt
            // offers casts only. Hydration resets the drop, so the consult
            // must re-derive "drop spent" from the prompt — otherwise the AI
            // holds every spell waiting to play a land it can no longer play.
            playLand()
            val aar = allMessages.last { it.hasActionsAvailableReq() }
            aar.actionsAvailableReq.actionsList.none {
                it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Play_add3
            } shouldBe
                true

            val result = advise(aar, leyline.tooling.headless.HeadlessAdviceMode.Snapshot)

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

        session(
            "consult targets the OPPONENT player for a player-burn spell",
            puzzle = CONSULT_TARGETS_OPPONENT_PLAYER_PUZZLE,
        ) {
            // Cast in the source game up to the targeting prompt, then consult
            // the snapshot about that prompt. The hydrated game has no bound
            // targeting ability, so this exercises the required-target path
            // where both candidates are players — the pick must be the
            // opponent's face (seat 2), never our own.
            castSpellByName("Lava Axe").shouldBeTrue()
            val targetsReq = allMessages.last { it.hasSelectTargetsReq() }

            val result = advise(targetsReq, leyline.tooling.headless.HeadlessAdviceMode.Snapshot)

            result.proposal.intent shouldBe "target"
            result.proposal.responseIds shouldBe listOf(2)
        }

        session(
            "target consult reproduces the live decision byte-for-byte (rebuilt ability)",
            puzzle = TARGET_CONSULT_REPRODUCES_LIVE_PUZZLE,
        ) {
            // Multiple legal targets (two enemy creatures + both faces): the
            // pre-rebuild fallback picked by list order, not by the AI's
            // judgement. The contract under test: the hydrated consult routes
            // through the same ability the live game consults, so the response
            // bytes are identical — whatever the AI's pick is.
            castSpellByName("Shock").shouldBeTrue()
            val targetsReq = allMessages.last { it.hasSelectTargetsReq() }

            val live = advise(targetsReq)
            live.proposal.responses.shouldNotBeEmpty()

            val result = advise(targetsReq, leyline.tooling.headless.HeadlessAdviceMode.Snapshot)

            result.proposal.responses shouldBe live.proposal.responses
        }

        session(
            "bounded target consult realizes zero-to-two and one-to-two groups",
            puzzle = BOUNDED_TARGET_CONSULT_REALIZES_PUZZLE,
        ) {
            val candidateIds = listOf(human.battlefield.iid("Grizzly Bears"), ai.battlefield.iid("Raging Goblin"))

            fun prompt(
                min: Int,
                max: Int,
                msgId: Int,
            ): GREToClientMessage =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SelectTargetsReq_695e)
                    .setMsgId(msgId)
                    .setGameStateId(observe().client.latestGsId)
                    .addSystemSeatIds(1)
                    .setSelectTargetsReq(
                        SelectTargetsReq
                            .newBuilder()
                            .addTargets(
                                TargetSelection
                                    .newBuilder()
                                    .setTargetIdx(1)
                                    .setMinTargets(min)
                                    .setMaxTargets(max)
                                    .apply {
                                        candidateIds.forEach { instanceId ->
                                            addTargets(
                                                Target
                                                    .newBuilder()
                                                    .setTargetInstanceId(instanceId)
                                                    .setLegalAction(SelectAction.Select_a1ad),
                                            )
                                        }
                                    },
                            ),
                    ).build()

            fun consult(prompt: GREToClientMessage): ClientToGREMessage = decodeSingle(advise(prompt).proposal.responses.single())

            consult(prompt(min = 0, max = 2, msgId = 61)).type shouldBe ClientMessageType.SubmitTargetsReq
            val required = consult(prompt(min = 1, max = 2, msgId = 62))
            required.type shouldBe ClientMessageType.SelectTargetsResp_097b
            required.selectTargetsResp.target.targetsList shouldHaveSize 1
            required.selectTargetsResp.target.targetIdx shouldBe 1
        }

        session(
            "multi-group target consult advances one group per echoed prompt",
            puzzle = MULTI_GROUP_TARGET_CONSULT_PUZZLE,
        ) {
            val ownIds = listOf(human.battlefield.iid("Grizzly Bears"), human.battlefield.iid("Walking Corpse"))
            val opponentIds = listOf(ai.battlefield.iid("Raging Goblin"), ai.battlefield.iid("Centaur Courser"))

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
                    .setGameStateId(observe().client.latestGsId)
                    .addSystemSeatIds(1)
                    .setSelectTargetsReq(
                        SelectTargetsReq
                            .newBuilder()
                            .addTargets(group(1, ownIds, selectedOwn))
                            .addTargets(group(2, opponentIds, selectedOpponent)),
                    ).build()

            fun consult(prompt: GREToClientMessage): ClientToGREMessage = decodeSingle(advise(prompt).proposal.responses.single())

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

        session(
            "modal consult retains the prompt ctoId in proposal and response",
            puzzle = MODAL_CONSULT_RETAINS_PROMPT_PUZZLE,
        ) {
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.CastingTimeOptionsReq_695e)
                    .setMsgId(91)
                    .setGameStateId(observe().client.latestGsId)
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

            val result = advise(prompt)

            result.proposal.intent shouldBe "modal"
            result.proposal.ctoId shouldBe 3
            result.proposal.modalGrpIds shouldBe listOf(42_001)
            val response = decodeSingle(result.proposal.responses.single())
            response.castingTimeOptionsResp.castingTimeOptionResp.ctoId shouldBe 3
            response.castingTimeOptionsResp.castingTimeOptionResp.chooseModalResp.grpIdsList shouldBe listOf(42_001)
        }
    })

private val CONSULT_PROPOSES_LETHAL_BOLT_PUZZLE =
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

private val CONSULT_PROPOSES_CAST_AFTER_PUZZLE =
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

private val CONSULT_TARGETS_OPPONENT_PLAYER_PUZZLE =
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

private val TARGET_CONSULT_REPRODUCES_LIVE_PUZZLE =
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

private val BOUNDED_TARGET_CONSULT_REALIZES_PUZZLE =
    """
    [metadata]
    Name:Snapshot Consult Bounded Targets
    Goal:Win
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Grizzly Bears
    humanlibrary=Forest
    aibattlefield=Raging Goblin
    ailibrary=Mountain
    """.trimIndent()

private val MULTI_GROUP_TARGET_CONSULT_PUZZLE =
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

private val MODAL_CONSULT_RETAINS_PROMPT_PUZZLE =
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
