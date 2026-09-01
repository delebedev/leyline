package leyline.tooling.simclient

import leyline.copilot.PromptDecisionResult
import leyline.copilot.PromptDecisionSource
import leyline.copilot.SimDecision
import leyline.tooling.headless.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** Consult a reconstructed state and return its desired response to the headless owner. */
internal class SnapshotPromptDriver(
    harness: MatchFlowHarness,
    private val source: SnapshotProposalSource = SnapshotProposalSource(harness),
) {
    private val consulted = mutableMapOf<String, Int>()
    private val chose = mutableMapOf<String, Int>()
    private val totalMs = mutableMapOf<String, Long>()
    private val maxMs = mutableMapOf<String, Long>()
    private val unavailable = mutableMapOf<String, Int>()
    private val fidelityGrades = mutableMapOf<String, Int>()
    private val importFindings = mutableMapOf<String, Int>()
    private val decisionSources = mutableMapOf<String, Int>()

    fun respond(prompt: GREToClientMessage): SimPromptResponse? {
        val key = prompt.type.name.removeSuffix("_695e")
        consulted.merge(key, 1, Int::plus)
        val started = System.nanoTime()
        val consultation =
            try {
                source.decide(prompt)
            } catch (failure: Throwable) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                totalMs.merge(key, elapsedMs, Long::plus)
                maxMs.merge(key, elapsedMs, ::maxOf)
                unavailable.merge("ConsultFailed", 1, Int::plus)
                log.warn("snapshot consult {} failed: {}", prompt.type, failure.message)
                return null
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        totalMs.merge(key, elapsedMs, Long::plus)
        maxMs.merge(key, elapsedMs, ::maxOf)

        fidelityGrades.merge(consultation.fidelity.grade, 1, Int::plus)
        consultation.fidelity.unavailableReasons.forEach { importFindings.merge(it, 1, Int::plus) }
        val result = consultation.result
        if (result is PromptDecisionResult.Unavailable) {
            val reason = "${result.reason.name}:${result.detail}"
            unavailable.merge(result.reason.name, 1, Int::plus)
            log.warn("snapshot consult {} unavailable: {}", prompt.type, reason)
            return null
        }
        result as PromptDecisionResult.Chosen
        decisionSources.merge(result.source.name, 1, Int::plus)
        val decision = result.decision
        if (result.source == PromptDecisionSource.ForgeAi) chose.merge(key, 1, Int::plus)
        val fingerprint =
            (decision as? SimDecision.PerformAction)
                ?.action
                ?.takeUnless { it.actionType == ActionType.Pass }
                ?.actionFingerprint()
        return SimPromptResponse(decision, aarActionFingerprint = fingerprint)
    }

    fun telemetry(): SimPromptPolicyTelemetry =
        SimPromptPolicyTelemetry(
            consulted = consulted.toMap(),
            chose = chose.toMap(),
            totalMs = totalMs.toMap(),
            maxMs = maxMs.toMap(),
            targetChoices = emptyMap(),
            targetChoiceSamples = emptyMap(),
            advisorUnavailableByReason = unavailable.toMap(),
        )

    fun fidelityGrades(): Map<String, Int> = fidelityGrades.toMap()

    fun importFindings(): Map<String, Int> = importFindings.toMap()

    fun decisionSources(): Map<String, Int> = decisionSources.toMap()

    private companion object {
        val log = LoggerFactory.getLogger(SnapshotPromptDriver::class.java)
    }
}
