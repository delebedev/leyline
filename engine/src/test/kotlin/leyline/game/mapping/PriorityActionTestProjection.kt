package leyline.game.mapping

import leyline.bridge.PriorityActionCandidates
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq

internal fun projectPriorityWindowForTest(
    seatId: Int,
    snapshot: GsmSnapshot,
    bridge: GameBridge,
): ActionMapper.ActionProjection {
    val player = checkNotNull(bridge.getPlayer(SeatId(seatId)))
    val candidates = PriorityActionCandidates.query(checkNotNull(bridge.getGame()), player)
    val preparation = ActionMapper.prepareFromSnapshot(seatId, snapshot, bridge, candidates)
    val actionBridge = bridge.seat(SeatId(seatId)).action
    val pending = checkNotNull(actionBridge.getPending())
    val tokens = checkNotNull(actionBridge.prepareActionTokens(pending.actionId, preparation.commands))
    return PriorityActionProjector.project(preparation.bindTokens(pending.actionId, tokens), bridge::getOrAllocInstanceId)
}

internal fun buildPriorityActionsForTest(
    seatId: Int,
    snap: GsmSnapshot,
    bridge: GameBridge,
    priorityCandidates: PriorityActionCandidates? = null,
    idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
) = PriorityActionProjector.project(
    ActionMapper
        .prepareFromSnapshot(
            seatId,
            snap,
            bridge,
            priorityCandidates
                ?: PriorityActionCandidates.query(
                    checkNotNull(bridge.getGame()),
                    checkNotNull(bridge.getPlayer(SeatId(seatId))),
                ),
        ).actions,
    idResolver,
)

internal fun buildPriorityActionsForTest(
    seatId: Int,
    bridge: GameBridge,
): ActionsAvailableReq =
    buildPriorityActionsForTest(
        seatId = seatId,
        snap = SnapshotCapture.run(checkNotNull(bridge.getGame()), bridge, "test", 0),
        bridge = bridge,
    )
