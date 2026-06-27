package leyline.game.annotations

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.state.TargetSpecKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * TargetSpec annotations: one persistent [TargetSpecKind] pAnn per targeted
 * spell/ability on the stack (one per card target, 1-based index per group).
 * Pure and persistent-only — no transient stream output and no effect-id
 * allocation, so its invocation position is order-independent. Pruned by the
 * store's full-replacement upsert when the spell leaves the stack.
 *
 * Reads the pending target picks via a non-consuming snapshot; the spine keeps
 * its own snapshot for the consumed-spec records that flow into bridge mutations.
 */
object TargetSpecContributor : AnnotationContributor {
    override val rank: Int = 20

    override fun contribute(ctx: AnnotationContext): Contribution {
        val pending = ctx.bridge.snapshotPendingTargetSpecs().map { it.spec }
        return Contribution(persistent = mapOf(TargetSpecKind to buildTargetSpec(pending, ctx)))
    }

    private fun buildTargetSpec(
        pending: List<InteractivePromptBridge.PendingTarget>,
        ctx: AnnotationContext,
    ): List<AnnotationInfo> {
        // Read target picks recorded during selectTargetsInteractively.
        // The spell may have already resolved by now (auto-pass), so we can't
        // rely on scanning game.getStack() — the stack is often empty.
        if (pending.isEmpty()) return emptyList()

        val frameIds = ctx.frameIds
        // promptId still needs per-ability prompt-shape mapping. Fall back to
        // 0 until a local mapping exists for the targeting prompt copy.
        return pending.mapNotNull { spec ->
            // Use the iid recorded at target-pick time for non-triggers (see
            // PendingTarget KDoc for the multi-target-spell rationale).
            // Triggers defer to emission-time resolution via the SA id —
            // TargetingCoordinator always populates spec.forgeAbilityId when
            // spec.isTriggeredAbility=true, so that branch's fallback is
            // structurally unreachable and crashes under DevCheck.strict.
            val affectorIid =
                if (spec.affectorInstanceIdAtRecord != 0) {
                    InstanceId(spec.affectorInstanceIdAtRecord)
                } else if (spec.isTriggeredAbility) {
                    if (spec.forgeAbilityId != 0) {
                        frameIds.triggerStackAbilityIid(spec.forgeAbilityId)
                    } else {
                        DevCheck.fail {
                            "PendingTarget for ${spec.spellName} marked isTriggeredAbility but missing forgeAbilityId; " +
                                "every triggered-ability target spec must carry the SA id since stack-ability iids " +
                                "are SA-id-keyed"
                        }
                        // Emit 0 rather than the source-card-keyed iid — that
                        // would point at a non-existent stack object since
                        // ZoneMapper now mints via the SA-id-keyed surrogate.
                        // 0 surfaces visibly in invariant checks rather than
                        // routing the TargetSpec to a stale iid.
                        InstanceId(0)
                    }
                } else {
                    frameIds.cardIid(ForgeCardId(spec.spellForgeCardId))
                }
            val targetIid =
                when {
                    spec.targetForgeCardId != null ->
                        frameIds.cardIid(ForgeCardId(spec.targetForgeCardId))
                    // Player target: Arena uses seatId (1 or 2) as the iid for player entities.
                    spec.targetSeatId != null -> InstanceId(spec.targetSeatId)
                    else -> return@mapNotNull null
                }
            val abilityGrpId = ctx.targetSpecAbilityGrpId(spec)
            AnnotationBuilder.targetSpec(
                instanceId = targetIid,
                affectorId = affectorIid,
                abilityGrpId = GrpId(abilityGrpId),
                index = spec.index,
                promptId = spec.promptId ?: 0,
                promptParameters = affectorIid.value,
            )
        }
    }
}
