package leyline.game.mapping

import leyline.game.annotations.TransferResult
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.LinkedFaceRole
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/** Projects Adventure face companions without adding them to zone membership. */
object AdventureCompanionProjector {
    fun append(
        transferResult: TransferResult,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): TransferResult {
        val companions =
            snap.boundCards.values.mapNotNull { bound ->
                val face = bound.linkedFaces.singleOrNull { it.role == LinkedFaceRole.Adventure } ?: return@mapNotNull null
                val parentIid = frameIds.cardIid(bound.forgeCardId)
                val parent =
                    transferResult.patchedObjects.firstOrNull { obj ->
                        obj.instanceId == parentIid.value && obj.type == GameObjectType.Card
                    } ?: return@mapNotNull null
                val companionIid =
                    bridge.getOrAllocInstanceId(FrameIdResolver.adventureCompanionForgeId(parentIid)).value
                ObjectMapper.buildAdventureObject(face, companionIid, parent, bridge.cardProto)
            }
        return transferResult.copy(patchedObjects = transferResult.patchedObjects + companions)
    }

    /** Reconstruct companion ids projected for a prior snapshot and viewer. */
    fun instanceIds(
        snap: GsmSnapshot,
        bridge: GameBridge,
        viewingSeatId: Int,
    ): Set<Int> =
        snap.boundCards.values
            .asSequence()
            .filter { bound -> bound.linkedFaces.any { it.role == LinkedFaceRole.Adventure } }
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
            }.mapTo(mutableSetOf()) { bound ->
                val parentIid = bridge.getOrAllocInstanceId(bound.forgeCardId)
                bridge.getOrAllocInstanceId(FrameIdResolver.adventureCompanionForgeId(parentIid)).value
            }
}
