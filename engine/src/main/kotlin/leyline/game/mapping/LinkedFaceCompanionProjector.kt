package leyline.game.mapping

import leyline.game.annotations.TransferResult
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.LinkedFaceRole
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/** Projects supported secondary-face companions without adding them to zone membership. */
object LinkedFaceCompanionProjector {
    fun append(
        transferResult: TransferResult,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): TransferResult {
        val parents = transferResult.patchedObjects.toMutableList()
        val transientHiddenFamilyIds = mutableSetOf<Int>()
        val companions =
            snap.boundCards.values.flatMap { bound ->
                val parentIid = frameIds.cardIid(bound.forgeCardId)
                val parent =
                    parents.firstOrNull { obj ->
                        obj.instanceId == parentIid.value && obj.type == GameObjectType.Card
                    } ?: buildTransferredParent(bound, parentIid.value, transferResult, bridge)?.also { transferredParent ->
                        parents.add(transferredParent)
                        transientHiddenFamilyIds.add(transferredParent.instanceId)
                    }
                        ?: return@flatMap emptyList()
                bound.linkedFaces.map { face ->
                    val projectedFace = face.copy(grpId = selectedStackFaceGrpId(parentIid.value, face.role, transferResult) ?: face.grpId)
                    val companionIid =
                        bridge
                            .getOrAllocInstanceId(FrameIdResolver.linkedFaceCompanionForgeId(parentIid, face.role))
                            .value
                    ObjectMapper.buildLinkedFaceObject(projectedFace, companionIid, parent, bridge.cardProto).also { companion ->
                        if (parent.instanceId in transientHiddenFamilyIds) {
                            transientHiddenFamilyIds.add(companion.instanceId)
                        }
                    }
                }
            }
        return transferResult.copy(
            patchedObjects = parents + companions,
            transientHiddenFamilyIds = transientHiddenFamilyIds,
        )
    }

    private fun selectedStackFaceGrpId(
        parentIid: Int,
        role: LinkedFaceRole,
        transferResult: TransferResult,
    ): Int? =
        transferResult.transfers
            .firstOrNull { transfer ->
                transfer.newId == parentIid &&
                    when (role) {
                        LinkedFaceRole.Adventure -> transfer.isAdventureCast
                        LinkedFaceRole.Omen -> transfer.isOmenCast
                    }
            }?.grpId

    private fun buildTransferredParent(
        bound: BoundCard,
        parentIid: Int,
        transferResult: TransferResult,
        bridge: GameBridge,
    ) = transferResult.transfers
        .firstOrNull { transfer -> transfer.forgeCardId == bound.forgeCardId && transfer.newId == parentIid }
        ?.let { transfer ->
            ObjectMapper.buildFromSnapshot(
                cardSnap = bound.snapshot,
                instanceId = parentIid,
                zoneId = transfer.destZoneId,
                ownerSeatId = transfer.ownerSeatId,
                cardProto = bridge.cardProto,
                visibility = Visibility.Public,
                parentLinkage = bound.parentLinkage,
            )
        }

    /** Reconstruct companion ids projected for a prior snapshot and viewer. */
    fun instanceIds(
        snap: GsmSnapshot,
        bridge: GameBridge,
        viewingSeatId: Int,
    ): Set<Int> =
        snap.boundCards.values
            .asSequence()
            .filter { bound ->
                val zoneId =
                    snap.zones.values
                        .firstOrNull { bound.forgeCardId in it.contents }
                        ?.id
                when (zoneId) {
                    ZoneIds.P1_LIBRARY, ZoneIds.P2_LIBRARY -> false
                    ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.P1_SIDEBOARD, ZoneIds.P2_SIDEBOARD ->
                        viewingSeatId == 0 || viewingSeatId == bound.snapshot.owner.value
                    else -> true
                }
            }.flatMap { bound ->
                val parentIid = bridge.getOrAllocInstanceId(bound.forgeCardId)
                val zoneId =
                    snap.zones.values
                        .firstOrNull { bound.forgeCardId in it.contents }
                        ?.id
                bound.linkedFaces.asSequence().mapNotNull { face ->
                    val surrogate = FrameIdResolver.linkedFaceCompanionForgeId(parentIid, face.role)
                    if (zoneId == ZoneIds.P1_LIBRARY || zoneId == ZoneIds.P2_LIBRARY) {
                        bridge.peekInstanceId(surrogate)?.value
                    } else {
                        bridge.getOrAllocInstanceId(surrogate).value
                    }
                }
            }.toSet()

    fun isCompanionType(type: GameObjectType): Boolean = type in companionTypes

    private val companionTypes =
        leyline.game.snapshot.LinkedFaceRole.entries
            .mapTo(mutableSetOf()) { it.objectType }
}
