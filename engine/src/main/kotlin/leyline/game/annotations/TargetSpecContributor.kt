package leyline.game.annotations

import leyline.DevCheck
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.StackTargetGroup
import leyline.game.mapping.PromptIds
import leyline.game.snapshot.StackEntry
import leyline.game.state.TargetSpec
import leyline.game.state.TargetSpecKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * TargetSpec annotations: one persistent [TargetSpecKind] pAnn per targeted
 * spell/ability target group on the stack.
 * Pure and persistent-only — no transient stream output and no effect-id
 * allocation, so its invocation position is order-independent. Groups remain
 * active while their common affector is on the stack and expire together when
 * that spell or ability resolves.
 *
 * Reads the pending target picks via a non-consuming snapshot; the spine keeps
 * its own snapshot for the consumed-spec records that flow into bridge mutations.
 */
object TargetSpecContributor : AnnotationContributor {
    override val rank: Int = 20

    override fun contribute(ctx: AnnotationContext): Contribution {
        val pending = ctx.promptFacts.targetSpecs.map { it.spec }
        val submitted = buildTargetSpec(pending, ctx)
        return Contribution(persistent = mapOf(TargetSpecKind to submitted + buildStackTargetSpec(submitted, ctx)))
    }

    /**
     * Rows for chosen target groups of stack objects that no client submission
     * recorded, such as an opponent's spell whose targets Forge chose. A group
     * already published, or published by [buildTargetSpec] this frame, keeps
     * its submitted row.
     */
    private fun buildStackTargetSpec(
        submitted: List<AnnotationInfo>,
        ctx: AnnotationContext,
    ): List<AnnotationInfo> {
        val stack = ctx.snap.stack
        if (stack.entries.none { it.targetGroups.isNotEmpty() }) return emptyList()
        val published =
            (ctx.editor.persistentAnnotations.activeAnnotations.values + submitted)
                .filter(TargetSpecKind::matches)
                .mapTo(hashSetOf(), TargetSpecKind::identityKey)
        return stack.entries
            .filter { it.targetGroups.isNotEmpty() && it != stack.resolvingEntry }
            .flatMap { entry ->
                val affectorIid =
                    if (entry.isSpell) {
                        ctx.frameIds.cardIid(entry.forgeCardId)
                    } else {
                        InstanceId(ctx.stackAbilityIid(entry.forgeAbilityId, entry.forgeCardId))
                    }
                entry.targetGroups.mapNotNull { group ->
                    if ((affectorIid.value to group.index) in published) return@mapNotNull null
                    val targetIids =
                        group.affectees.mapNotNull { affectee ->
                            affectee.cardId?.let { ctx.frameIds.cardIid(ForgeCardId(it)) }
                                ?: affectee.seatId?.let(::InstanceId)
                        }
                    if (targetIids.size != group.affectees.size) return@mapNotNull null
                    val distributions = group.affectees.mapNotNull { it.distribution }
                    AnnotationBuilder.targetSpec(
                        instanceIds = targetIids,
                        affectorId = affectorIid,
                        abilityGrpId = GrpId(stackGroupAbilityGrpId(entry, group, ctx)),
                        index = group.index,
                        promptId = group.promptId,
                        promptParameters = affectorIid.value,
                        distributions = distributions.takeIf { it.size == targetIids.size }.orEmpty(),
                    )
                }
            }
    }

    private fun stackGroupAbilityGrpId(
        entry: StackEntry,
        group: StackTargetGroup,
        ctx: AnnotationContext,
    ): Int =
        when {
            group.promptId == PromptIds.MUTATE_TARGET -> 0
            group.abilityGrpId != 0 -> group.abilityGrpId
            entry.isSpell -> ctx.environment.cardReferences.targetSpecAbilityGrpId(entry.sourceCardGrpId)
            else -> entry.grpId
        }

    private fun buildTargetSpec(
        pending: List<TargetSpec>,
        ctx: AnnotationContext,
    ): List<AnnotationInfo> {
        // Read target picks after Forge completes chooseTargetsFor.
        // The spell may have already resolved by now (auto-pass), so we can't
        // rely on scanning game.getStack() — the stack is often empty.
        if (pending.isEmpty()) return emptyList()

        val frameIds = ctx.frameIds
        return pending.mapNotNull { spec ->
            // Use the iid recorded at target-pick time for non-triggers (see
            // PendingTarget KDoc for the multi-target-spell rationale).
            // Stack abilities defer to emission-time resolution via the SA id —
            // TargetingCoordinator always populates spec.forgeAbilityId when
            // spec.isStackAbility=true, so that branch's fallback is
            // structurally unreachable and crashes under DevCheck.strict.
            val affectorIid =
                if (spec.affectorInstanceIdAtRecord != 0) {
                    InstanceId(spec.affectorInstanceIdAtRecord)
                } else if (spec.isStackAbility) {
                    if (spec.forgeAbilityId != 0) {
                        ctx.targetSpecStackAbilityIid(spec)
                    } else {
                        DevCheck.fail {
                            "PendingTarget for ${spec.spellName} marked isStackAbility but missing forgeAbilityId; " +
                                "every stack-ability target spec must carry the SA id since stack-ability iids " +
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
            val targetIids =
                spec.affectees.mapNotNull { affectee ->
                    when {
                        affectee.targetForgeCardId != null -> frameIds.cardIid(ForgeCardId(affectee.targetForgeCardId))
                        affectee.targetSeatId != null -> InstanceId(affectee.targetSeatId)
                        else -> null
                    }
                }
            if (targetIids.isEmpty()) return@mapNotNull null
            val distributions = spec.affectees.mapNotNull { it.distribution }
            val alignedDistributions = distributions.takeIf { it.size == targetIids.size }.orEmpty()
            val abilityGrpId = ctx.targetSpecAbilityGrpId(spec)
            AnnotationBuilder.targetSpec(
                instanceIds = targetIids,
                affectorId = affectorIid,
                abilityGrpId = GrpId(abilityGrpId),
                index = spec.index,
                promptId = spec.promptId ?: leyline.game.mapping.PromptIds.SELECT_TARGETS,
                promptParameters = affectorIid.value,
                distributions = alignedDistributions,
            )
        }
    }
}
