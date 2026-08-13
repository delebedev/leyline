package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.CrewedThisTurnKind
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.ModifiedTypeForCrewKind
import leyline.game.state.SaddledThisTurnKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Vehicle/Attach annotations: Crew, Saddle, and Reconfigure share one
 * frame-fact → (transient, persistent) shape, so they collapse into a single
 * [AnnotationContributor]. Crew and Reconfigure both feed [ModifiedTypeForCrewKind];
 * the pipeline concatenates those persistent lists.
 */
object VehicleAttachContributor : AnnotationContributor {
    override val rank: Int = 50

    override fun contribute(ctx: AnnotationContext): Contribution {
        val (crewedThisTurn, crewTypeChange, crewExpired) = computeCrewAnnotations(ctx)
        val (reconfigureTransient, reconfigurePersistent) = computeReconfigureAnnotations(ctx)
        val saddledThisTurn = computeSaddleAnnotations(ctx.effectFacts, ctx.frameIds)
        return Contribution(
            transient = crewExpired + reconfigureTransient,
            persistent =
                mapOf(
                    CrewedThisTurnKind to crewedThisTurn,
                    SaddledThisTurnKind to saddledThisTurn,
                    ModifiedTypeForCrewKind to crewTypeChange + reconfigurePersistent,
                ),
        )
    }

    /** Crew scan: CrewedThisTurn pAnns, ModifiedType pAnns, and expired effect annotations. */
    private fun computeCrewAnnotations(ctx: AnnotationContext): Triple<List<AnnotationInfo>, List<AnnotationInfo>, List<AnnotationInfo>> {
        val crewSnapshots = ctx.effectFacts.crewStates
        val crewedThisTurn =
            crewSnapshots.map { snap ->
                AnnotationBuilder.crewedThisTurn(
                    ctx.frameIds.cardIid(snap.vehicleForgeCardId),
                    snap.crewSourceForgeCardIds.map(ctx.frameIds::cardIid),
                )
            }
        val typeChange = mutableListOf<AnnotationInfo>()
        val expired = mutableListOf<AnnotationInfo>()

        val currentCrewedFids = crewSnapshots.filter { it.isCreature }.map { it.vehicleForgeCardId }.toSet()
        for (effectId in ctx.effects.crew.releaseMissing(currentCrewedFids)) {
            expired.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }
        for (snap in crewSnapshots) {
            if (!snap.isCreature) continue
            val effectId = EffectId(ctx.effects.crew.getOrAllocId(snap.vehicleForgeCardId))
            typeChange.add(
                AnnotationBuilder.modifiedTypeLayeredEffect(
                    instanceId = ctx.frameIds.cardIid(snap.vehicleForgeCardId),
                    effectId = effectId,
                    sourceAbilityGrpId = snap.crewAbilityGrpId?.let { GrpId(it) },
                ),
            )
        }
        return Triple(crewedThisTurn, typeChange, expired)
    }

    /** Saddle scan: SaddledThisTurn pAnns for mounts and helper creatures. */
    private fun computeSaddleAnnotations(
        facts: EffectProjectionFacts,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        facts.saddleStates.map { snap ->
            AnnotationBuilder.saddledThisTurn(
                frameIds.cardIid(snap.mountForgeCardId),
                snap.saddleSourceForgeCardIds.map(frameIds::cardIid),
            )
        }

    private fun computeReconfigureAnnotations(ctx: AnnotationContext): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val snapshots = ctx.effectFacts.reconfigureStates
        val current = snapshots.filter { it.isAttached }.map { it.forgeCardId }.toSet()
        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        for (effectId in ctx.effects.reconfigure.releaseMissing(current)) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        for (snap in snapshots) {
            if (!snap.isAttached) continue
            val allocation = ctx.effects.reconfigure.getOrAlloc(snap.forgeCardId)
            val sourceIid = ctx.frameIds.cardIid(snap.forgeCardId)
            val effectId = EffectId(allocation.effectId)
            if (allocation.created) {
                transient.add(AnnotationBuilder.layeredEffectCreated(effectId, sourceIid))
            }
            persistent.add(
                AnnotationBuilder.modifiedTypeLayeredEffect(
                    instanceId = sourceIid,
                    effectId = effectId,
                    affectorId = sourceIid,
                    sourceAbilityGrpId = snap.attachAbilityGrpId?.let(::GrpId),
                ),
            )
        }

        return transient to persistent
    }
}
