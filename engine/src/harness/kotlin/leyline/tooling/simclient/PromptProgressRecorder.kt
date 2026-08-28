package leyline.tooling.simclient

import leyline.copilot.SimDecision
import leyline.tooling.headless.MatchFlowHarness
import leyline.tooling.simclient.PromptProgressSample
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal class PromptProgressRecorder(
    private val harness: MatchFlowHarness,
    private val maxSamples: Int = 50,
) {
    private val samples = ArrayDeque<PromptProgressSample>()

    fun record(
        prompt: ActivePrompt,
        decision: SimDecision,
        submitResult: SimSubmitResult,
        beforeMessages: Int,
        beforeLast: GREToClientMessage?,
        sourceBefore: String,
    ) = record(
        prompt = prompt,
        value = SimPromptResponseValue.Decision(decision),
        submitResult = submitResult,
        beforeMessages = beforeMessages,
        beforeLast = beforeLast,
        sourceBefore = sourceBefore,
    )

    fun record(
        prompt: ActivePrompt,
        value: SimPromptResponseValue,
        submitResult: SimSubmitResult,
        beforeMessages: Int,
        beforeLast: GREToClientMessage?,
        sourceBefore: String,
    ) = record(
        prompt = prompt,
        decisionKind = value.kind,
        targetIds = value.targetIds(),
        submitResult = submitResult,
        beforeMessages = beforeMessages,
        beforeLast = beforeLast,
        sourceBefore = sourceBefore,
    )

    fun record(
        prompt: ActivePrompt,
        decisionKind: String,
        targetIds: List<Int>,
        submitResult: SimSubmitResult,
        beforeMessages: Int,
        beforeLast: GREToClientMessage?,
        sourceBefore: String,
    ) {
        val afterLast = harness.allMessages.lastOrNull()
        val (sourceInstanceId, _, abilityGrpId) = prompt.sourceFields()
        val sourceGrpId = harness.accumulator.objects[sourceInstanceId]?.grpId ?: 0
        val sample =
            PromptProgressSample(
                promptType = prompt.type.name,
                decisionKind = decisionKind,
                submitResult = submitResult.name,
                promptMsgId = prompt.msgId,
                promptGameStateId = prompt.msg.gameStateId,
                beforeMsgId = beforeLast?.msgId ?: 0,
                beforeGameStateId = beforeLast?.gameStateId ?: 0,
                afterMsgId = afterLast?.msgId ?: 0,
                afterGameStateId = afterLast?.gameStateId ?: 0,
                beforeMessages = beforeMessages,
                afterMessages = harness.allMessages.size,
                sourceInstanceId = sourceInstanceId,
                sourceGrpId = sourceGrpId,
                abilityGrpId = abilityGrpId,
                targetIds = targetIds,
                sourceBefore = sourceBefore,
                sourceAfter = objectSnapshot(sourceInstanceId),
            )
        samples += sample
        while (samples.size > maxSamples) samples.removeFirst()
    }

    fun snapshot(): List<PromptProgressSample> = samples.toList()

    fun sourceSnapshot(prompt: ActivePrompt): String = objectSnapshot(prompt.sourceFields().first)

    fun objectSnapshot(instanceId: Int): String {
        if (instanceId == 0) return ""
        val obj = harness.accumulator.objects[instanceId] ?: return "missing:$instanceId"
        val zone = zoneName(obj.zoneId)
        return "id=${obj.instanceId};grp=${obj.grpId};zone=$zone;" +
            "ctrl=${obj.controllerSeatId};type=${obj.type.name}"
    }

    private fun zoneName(zoneId: Int): String =
        when (zoneId) {
            leyline.game.mapping.ZoneIds.REVEALED_P1 -> "REVEALED_P1"
            leyline.game.mapping.ZoneIds.REVEALED_P2 -> "REVEALED_P2"
            leyline.game.mapping.ZoneIds.SUPPRESSED -> "SUPPRESSED"
            leyline.game.mapping.ZoneIds.PENDING -> "PENDING"
            leyline.game.mapping.ZoneIds.COMMAND -> "COMMAND"
            leyline.game.mapping.ZoneIds.STACK -> "STACK"
            leyline.game.mapping.ZoneIds.BATTLEFIELD -> "BATTLEFIELD"
            leyline.game.mapping.ZoneIds.EXILE -> "EXILE"
            leyline.game.mapping.ZoneIds.LIMBO -> "LIMBO"
            leyline.game.mapping.ZoneIds.P1_HAND -> "P1_HAND"
            leyline.game.mapping.ZoneIds.P1_LIBRARY -> "P1_LIBRARY"
            leyline.game.mapping.ZoneIds.P1_GRAVEYARD -> "P1_GRAVEYARD"
            leyline.game.mapping.ZoneIds.P1_SIDEBOARD -> "P1_SIDEBOARD"
            leyline.game.mapping.ZoneIds.P2_HAND -> "P2_HAND"
            leyline.game.mapping.ZoneIds.P2_LIBRARY -> "P2_LIBRARY"
            leyline.game.mapping.ZoneIds.P2_GRAVEYARD -> "P2_GRAVEYARD"
            leyline.game.mapping.ZoneIds.P2_SIDEBOARD -> "P2_SIDEBOARD"
            else -> zoneId.toString()
        }
}

@Suppress("ElseCaseInsteadOfExhaustiveWhen")
private fun ActivePrompt.sourceFields(): Triple<Int, Int, Int> =
    when (type) {
        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.SelectTargetsReq_695e -> {
            val req = msg.selectTargetsReq
            Triple(req.sourceId, 0, req.abilityGrpId)
        }
        else -> Triple(0, 0, 0)
    }

private fun SimPromptResponseValue.targetIds(): List<Int> =
    when (this) {
        is SimPromptResponseValue.Decision -> this.decision.targetIds()
        SimPromptResponseValue.RetirePrompt,
        SimPromptResponseValue.WaitForEngine,
        SimPromptResponseValue.Terminal,
        -> emptyList()
    }

private fun SimDecision.targetIds(): List<Int> =
    when (this) {
        is SimDecision.SelectTargets -> targetInstanceIds
        is SimDecision.SelectN -> selectedInstanceIds
        is SimDecision.Order -> orderedInstanceIds
        is SimDecision.Distribution -> amountsByInstanceId.keys.toList()
        is SimDecision.Search -> itemsFound
        is SimDecision.GroupedSearch -> itemsFound
        is SimDecision.SelectReplacement -> listOf(replacement.affectedObject)
        is SimDecision.EffectCost -> selectedInstanceIds
        is SimDecision.GroupTop -> instanceIds
        is SimDecision.GroupAway -> awayInstanceIds
        is SimDecision.DeclareAttackers -> attackerInstanceIds
        is SimDecision.DeclareBlockers -> assignments.keys.toList()
        is SimDecision.PerformAction -> listOfNotNull(action.instanceId.takeIf { it != 0 })
        is SimDecision.UnselectTargets,
        SimDecision.SubmitTargets,
        is SimDecision.AutoTapPayment,
        SimDecision.KeepHand,
        is SimDecision.OptionalAction,
        is SimDecision.OptionalCost,
        is SimDecision.CastingTimeX,
        is SimDecision.ModalChoice,
        is SimDecision.AlternateCost,
        is SimDecision.ManaTypeChoices,
        is SimDecision.NumericInput,
        is SimDecision.AssignDamage,
        SimDecision.DeclareAllAttackers,
        SimDecision.DeclareNoBlockers,
        is SimDecision.UndeclareBlocker,
        SimDecision.SubmitAttackers,
        SimDecision.SubmitBlockers,
        SimDecision.CancelAction,
        SimDecision.PassPriority,
        -> emptyList()
    }
