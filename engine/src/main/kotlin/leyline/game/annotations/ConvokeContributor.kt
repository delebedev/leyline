package leyline.game.annotations

import leyline.bridge.types.InstanceId
import leyline.game.mapping.ZoneIds
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Convoke resolve annotations: when a spell paid with Convoke leaves the stack
 * (Resolve), emit an AbilityWordActive("Convoke") transient on the resolving
 * object so the client shows the convoke word. Returns the exact observed
 * payment facts for the shell to consume after a successful commit.
 *
 * Transfer-stage emitter: it diffs this frame's zone transfers, so it reads
 * [AnnotationContext.transferResult] (required at its invocation site) rather
 * than the stage-4-5 snapshot. Transient-only — no persistent output.
 */
object ConvokeContributor : AnnotationContributor {
    override val rank: Int = 10

    data class Plan(
        val transient: List<AnnotationInfo>,
        val consumedPayments: List<PromptProjectionFacts.ConvokePaymentsFact>,
    )

    override fun contribute(ctx: AnnotationContext): Contribution = Contribution(transient = plan(ctx).transient)

    fun plan(ctx: AnnotationContext): Plan {
        val transferResult =
            requireNotNull(ctx.transferResult) {
                "ConvokeContributor requires AnnotationContext.transferResult; it is a transfer-stage emitter"
            }
        val annotations = mutableListOf<AnnotationInfo>()
        val consumed = mutableListOf<PromptProjectionFacts.ConvokePaymentsFact>()
        for (transfer in transferResult.transfers) {
            val sourceForgeCardId = transfer.forgeCardId ?: continue
            if (transfer.srcZoneId != ZoneIds.STACK) continue
            val payments = ctx.promptFacts.convokePayments.filter { it.sourceForgeCardId == sourceForgeCardId }
            if (payments.isEmpty()) continue

            if (transfer.category == TransferCategory.Resolve) {
                val resolvingId = InstanceId(transfer.newId.takeIf { it != 0 } ?: transfer.origId)
                annotations.add(AnnotationBuilder.abilityWordActive(resolvingId, "Convoke"))
            }
            consumed += payments
        }
        return Plan(annotations, consumed)
    }
}
