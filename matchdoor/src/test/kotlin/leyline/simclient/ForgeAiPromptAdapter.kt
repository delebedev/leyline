package leyline.simclient

import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

internal data class ForgeAiPromptContext(
    val harness: MatchFlowHarness,
    val forgeAi: ForgeAiPolicy,
    val attempts: ActionAttemptLedger,
)

internal interface ForgeAiPromptAdapter {
    val promptType: GREMessageType
    val telemetryName: String

    fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = true

    fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse?
}

internal object ForgeAiAarAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.ActionsAvailableReq_695e
    override val telemetryName: String = "ActionsAvailableReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = prompt.aarActions().any { it.isActionableAarAction() }

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val choice = context.forgeAi.chooseAarAction(prompt.aarActions(), context.attempts.skipFingerprints()) ?: return null
        return SimPromptResponse(
            decision = SimDecision.PerformAction(choice.action),
            aarActionFingerprint = choice.action.actionFingerprint(),
        )
    }
}

internal object ForgeAiDeclareAttackersAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.DeclareAttackersReq_695e
    override val telemetryName: String = "DeclareAttackersReq"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val attackers = context.forgeAi.chooseAttackers() ?: return null
        return SimPromptResponse(
            decision = SimDecision.DeclareAttackers(attackers),
            markHandled = false,
            markAllHandledOfType = prompt.type,
        )
    }
}

internal object ForgeAiDeclareBlockersAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.DeclareBlockersReq_695e
    override val telemetryName: String = "DeclareBlockersReq"

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val blockers = context.forgeAi.chooseBlockers() ?: return null
        return SimPromptResponse(SimDecision.DeclareBlockers(blockers))
    }
}

internal object ForgeAiSelectNAdapter : ForgeAiPromptAdapter {
    override val promptType: GREMessageType = GREMessageType.SelectNreq
    override val telemetryName: String = "SelectNReq"

    override fun shouldConsult(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): Boolean = context.forgeAi.canChooseSelectN(prompt.msg.selectNReq)

    override fun decide(
        prompt: ActivePrompt,
        context: ForgeAiPromptContext,
    ): SimPromptResponse? {
        val selected = context.forgeAi.chooseSelectN(prompt.msg.selectNReq) ?: return null
        return SimPromptResponse(SimDecision.SelectN(selected))
    }
}
