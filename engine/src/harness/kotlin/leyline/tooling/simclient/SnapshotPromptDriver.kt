package leyline.tooling.simclient

import leyline.copilot.CopilotProposal
import leyline.tooling.headless.MatchFlowHarness
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal data class SnapshotPromptResult(
    val proposal: CopilotProposal,
    val submitResult: SimSubmitResult,
)

/** Consult a reconstructed state and submit its encoded response to the active session. */
internal class SnapshotPromptDriver(
    private val harness: MatchFlowHarness,
    private val source: SnapshotProposalSource = SnapshotProposalSource(harness),
) {
    private val consulted = mutableMapOf<String, Int>()
    private val chose = mutableMapOf<String, Int>()
    private val totalMs = mutableMapOf<String, Long>()
    private val maxMs = mutableMapOf<String, Long>()

    fun respond(prompt: GREToClientMessage): SnapshotPromptResult {
        val key = prompt.type.name.removeSuffix("_695e")
        consulted.merge(key, 1, Int::plus)
        val started = System.nanoTime()
        val proposal = source.propose(prompt)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        totalMs.merge(key, elapsedMs, Long::plus)
        maxMs.merge(key, elapsedMs, ::maxOf)

        val encoded = proposal.responses.singleOrNull()
        if (encoded == null) {
            log.warn(
                "snapshot consult {} returned intent={} responses={}: {}",
                prompt.type,
                proposal.intent,
                proposal.responses.size,
                proposal.reason,
            )
            return SnapshotPromptResult(proposal, SimSubmitResult.NotSubmitted)
        }
        val message = decode(encoded)
        if (!harness.submitGameplayResponse(message)) {
            log.warn("snapshot consult produced unsupported response {} for {}", message.type, prompt.type)
            return SnapshotPromptResult(proposal, SimSubmitResult.NotSubmitted)
        }
        chose.merge(key, 1, Int::plus)
        return SnapshotPromptResult(proposal, SimSubmitResult.Submitted)
    }

    fun telemetry(): SimPromptPolicyTelemetry =
        SimPromptPolicyTelemetry(
            consulted = consulted.toMap(),
            chose = chose.toMap(),
            totalMs = totalMs.toMap(),
            maxMs = maxMs.toMap(),
            targetChoices = emptyMap(),
            targetChoiceSamples = emptyMap(),
            advisorUnavailableByReason = emptyMap(),
        )

    private fun decode(hex: String): ClientToGREMessage {
        require(hex.length % 2 == 0) { "odd-length response hex" }
        val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return ClientToGREMessage.parseFrom(bytes)
    }

    private companion object {
        val log = LoggerFactory.getLogger(SnapshotPromptDriver::class.java)
    }
}
