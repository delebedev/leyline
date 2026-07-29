package leyline.game.bundle

import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.BridgeMutations
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/** One explicitly correlated executable catalog planned for owner commit. */
data class ActionCatalogPlan(
    val actionId: String,
    val gameStateId: Int,
    val offers: List<ActionOffer>,
)

/**
 * Immutable output of one frame compilation.
 *
 * Delivery data is inspectable before commit. [BundleBuilder.commit] is the
 * only consumer of [projection], which advances shared projection state.
 */
class FramePlan internal constructor(
    val messages: List<GREToClientMessage>,
    val actionCatalog: ActionCatalogPlan?,
    internal val projection: ProjectionCommit,
) {
    internal data class ProjectionCommit(
        val counterBefore: MessageCounter.Snapshot,
        val counterAfter: MessageCounter.Snapshot,
        val nextBaseline: GsmSnapshot,
        val mutations: BridgeMutations?,
        val nextAnnotationId: Int?,
        val pendingSubmittedTargets: BundleCursor.PSuTPending?,
        val pendingOrderZoneMove: InteractivePromptBridge.PendingOrderZoneMove?,
        val bundleFrameReservation: GameBridge.BundleFrameReservation?,
        val observation: DiffObservation?,
    )

    internal data class DiffObservation(
        val previous: GsmSnapshot?,
        val current: GsmSnapshot,
        val events: FrameEventLog,
        val gameStateId: Int,
        val message: GameStateMessage,
    )

    internal fun delivery(): BundleBuilder.BundleResult =
        BundleBuilder.BundleResult(
            messages = messages,
            actionCatalog = actionCatalog,
        )
}
