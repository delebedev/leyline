package leyline.game.annotations

import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Convoke resolve annotations: when a spell paid with Convoke leaves the stack
 * (Resolve), emit an AbilityWordActive("Convoke") transient on the resolving
 * object so the client shows the convoke word. Clears the consumed convoke
 * payment journal entries as bridge bookkeeping.
 *
 * Transfer-stage emitter: it diffs this frame's zone transfers, so it reads
 * [AnnotationContext.transferResult] (required at its invocation site) rather
 * than the stage-4-5 snapshot. Transient-only — no persistent output.
 */
object ConvokeContributor : AnnotationContributor {
    override val rank: Int = 60

    override fun contribute(ctx: AnnotationContext): Contribution {
        val transferResult =
            requireNotNull(ctx.transferResult) {
                "ConvokeContributor requires AnnotationContext.transferResult; it is a transfer-stage emitter"
            }
        val bridge = ctx.bridge
        val annotations = mutableListOf<AnnotationInfo>()
        for (transfer in transferResult.transfers) {
            val sourceForgeCardId = transfer.forgeCardId ?: continue
            if (transfer.srcZoneId != ZoneIds.STACK) continue
            val promptBridges =
                bridge
                    .allSeatIds()
                    .sorted()
                    .map { bridge.promptBridge(SeatId(it)) }
                    .filter { it.journal.activeConvokePayments(sourceForgeCardId).isNotEmpty() }
            if (promptBridges.isEmpty()) continue

            if (transfer.category == TransferCategory.Resolve) {
                val resolvingId = InstanceId(transfer.newId.takeIf { it != 0 } ?: transfer.origId)
                annotations.add(AnnotationBuilder.abilityWordActive(resolvingId, "Convoke"))
            }
            promptBridges.forEach { it.journal.clearConvokePayments(sourceForgeCardId) }
        }
        return Contribution(transient = annotations)
    }
}
