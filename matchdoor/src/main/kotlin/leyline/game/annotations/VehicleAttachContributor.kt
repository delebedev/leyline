package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.state.CrewedThisTurnKind
import leyline.game.state.GameBridge
import leyline.game.state.ModifiedTypeForCrewKind
import leyline.game.state.SaddledThisTurnKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Vehicle/Attach annotations: Crew, Saddle, and Reconfigure share one
 * snapshot-diff → (transient, persistent) shape, so they collapse into a single
 * [AnnotationContributor]. Crew and Reconfigure both feed [ModifiedTypeForCrewKind];
 * the pipeline concatenates those persistent lists.
 */
object VehicleAttachContributor : AnnotationContributor {
    override val rank: Int = 50

    override fun contribute(ctx: AnnotationContext): Contribution {
        val bridge = ctx.bridge
        val (crewedThisTurn, crewTypeChange, crewExpired) = computeCrewAnnotations(bridge)
        val (reconfigureTransient, reconfigurePersistent) = computeReconfigureAnnotations(bridge)
        val saddledThisTurn = computeSaddleAnnotations(bridge)
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
    private fun computeCrewAnnotations(bridge: GameBridge): Triple<List<AnnotationInfo>, List<AnnotationInfo>, List<AnnotationInfo>> {
        val crewSnapshots = bridge.snapshotCrewState()
        val crewedThisTurn =
            crewSnapshots.map { snap ->
                AnnotationBuilder.crewedThisTurn(
                    InstanceId(snap.vehicleInstanceId),
                    snap.crewSourceInstanceIds.map { InstanceId(it) },
                )
            }
        val typeChange = mutableListOf<AnnotationInfo>()
        val expired = mutableListOf<AnnotationInfo>()

        val currentCrewedFids = crewSnapshots.filter { it.isCreature }.map { it.vehicleForgeCardId }.toSet()
        for (effectId in bridge.releaseCrewEffects(currentCrewedFids)) {
            expired.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }
        for (snap in crewSnapshots) {
            if (!snap.isCreature) continue
            val effectId = EffectId(bridge.getOrAllocCrewEffectId(snap.vehicleForgeCardId))
            typeChange.add(
                AnnotationBuilder.modifiedTypeLayeredEffect(
                    instanceId = InstanceId(snap.vehicleInstanceId),
                    effectId = effectId,
                    sourceAbilityGrpId = snap.crewAbilityGrpId?.let { GrpId(it) },
                ),
            )
        }
        return Triple(crewedThisTurn, typeChange, expired)
    }

    /** Saddle scan: SaddledThisTurn pAnns for mounts and helper creatures. */
    private fun computeSaddleAnnotations(bridge: GameBridge): List<AnnotationInfo> =
        bridge.snapshotSaddleState().map { snap ->
            AnnotationBuilder.saddledThisTurn(
                InstanceId(snap.mountInstanceId),
                snap.saddleSourceInstanceIds.map { InstanceId(it) },
            )
        }

    private fun computeReconfigureAnnotations(bridge: GameBridge): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val snapshots = bridge.snapshotReconfigureState()
        val current = snapshots.map { it.forgeCardId }.toSet()
        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        for (effectId in bridge.releaseReconfigureEffects(current)) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        for (snap in snapshots) {
            val allocation = bridge.getOrAllocReconfigureEffectId(snap.forgeCardId)
            val sourceIid = InstanceId(snap.instanceId)
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
