package leyline.bridge.coord

import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionAcknowledgements
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Prepared output and projection transition for one coordinator cut.
 *
 * Logical sequence and output order are part of [transition]. Discarding this value
 * consumes neither; installation commits both with projection and identity state.
 */
internal data class PreparedCut(
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
    val outputOrdinal: Long,
    val closesPlaybackFrame: Boolean,
) {
    companion object {
        fun prepare(
            prior: ProjectionState,
            planner: LogicalSequencePlanner,
            messages: List<GREToClientMessage>,
            projection: ProjectionTransition?,
            closesPlaybackFrame: Boolean,
        ): PreparedCut {
            projection?.let {
                check(it.expectedRevision == prior.revision) { "Projection and sequence must share one prior revision" }
            }
            planner.observe(messages)
            val ordinal = planner.allocateOutputOrdinal()
            val sequence = planner.snapshot()
            check(sequence.currentGsId >= prior.sequence.currentGsId) { "Prepared gsId cannot rewind committed sequence" }
            check(sequence.currentMsgId >= prior.sequence.currentMsgId) { "Prepared msgId cannot rewind committed sequence" }
            check(sequence.lastPromptGsId >= prior.sequence.lastPromptGsId) { "Prepared prompt gsId cannot rewind committed horizon" }
            check(sequence.lastPromptMsgId >= prior.sequence.lastPromptMsgId) { "Prepared prompt msgId cannot rewind committed horizon" }
            check(sequence.lastGameStateGsId >= prior.sequence.lastGameStateGsId) {
                "Prepared game-state gsId cannot rewind committed horizon"
            }
            check(ordinal == prior.sequence.committedOutputOrdinal + 1) { "Prepared output order must follow committed order" }
            val transition =
                ProjectionTransition(
                    expectedRevision = prior.revision,
                    nextState =
                        (projection?.nextState ?: prior.copy(revision = prior.revision + 1)).copy(
                            revision = prior.revision + 1,
                            sequence = sequence,
                        ),
                    acknowledgements = projection?.acknowledgements ?: ProjectionAcknowledgements(),
                )
            return PreparedCut(messages, transition, ordinal, closesPlaybackFrame)
        }
    }
}

/** One installed batch with the logical order assigned by its transition. */
internal data class CommittedOutputBatch(
    val ordinal: Long,
    val batchIndex: Int,
    val messages: List<GREToClientMessage>,
)

/** Family-owned observation points around one installation. Test seams only. */
internal data class CutInstallHooks(
    val beforeEnqueue: (() -> Unit)? = null,
    val beforeInstall: (() -> Unit)? = null,
    val afterInstall: (() -> Unit)? = null,
)

/**
 * Sole implementation of the coordinator cut transaction.
 *
 * Runs while the caller already holds the projection-build and feed locks. It
 * owns enqueue ordering, projection commit, rollback of its own batches
 * before install, playback acknowledgement, and typed failure handoff.
 *
 * It allocates no protocol messages, signals no priority, awaits no response,
 * and understands no prompt or action semantics. Callers keep their window
 * state, materializers, response validation, timeout policy, and side effects.
 */
internal class CoordinatorCutInstaller(
    private val owner: MatchCutCoordinator,
) {
    /**
     * Installs [cut] into [feed], then runs [onInstalled] inside the same
     * transaction once the cut is fully published.
     *
     * [replaces], when nonempty, is retired after the new batch is enqueued and
     * restored if projection installation fails. A failure before the projection
     * transition installs removes only this cut's batches; competing state and
     * unrelated queued output are untouched. When the transition did not install,
     * [onRollback] runs after the cut's batches are withdrawn
     * so the caller can undo side effects it owns alongside the cut. Any failure
     * is handed to [onFailure] so the family can attach its exact cut.
     */
    fun install(
        feed: MatchCutCoordinator.ViewerFeed,
        cut: PreparedCut,
        hooks: CutInstallHooks = CutInstallHooks(),
        batches: List<List<GREToClientMessage>> = listOf(cut.messages),
        replaces: List<GREToClientMessage> = emptyList(),
        onInstalled: (() -> Unit)? = null,
        onRollback: (() -> Unit)? = null,
        onFailure: (Throwable) -> Nothing,
    ) {
        check(batches.flatten() == cut.messages) { "Installed batches must contain the prepared cut messages in order" }
        val committedBatches =
            batches.mapIndexed { index, messages ->
                CommittedOutputBatch(cut.outputOrdinal, index, messages)
            }
        val enqueued = mutableListOf<CommittedOutputBatch>()
        var replaced: CommittedOutputBatch? = null
        var installed = false
        try {
            hooks.beforeEnqueue?.invoke()
            committedBatches.forEach { batch ->
                feed.beforeBatchEnqueue?.invoke(batch.batchIndex, batch.messages)
                feed.queue.add(batch)
                enqueued += batch
            }
            if (replaces.isNotEmpty()) {
                replaced = owner.takeOwnedBatch(feed, replaces)
                check(replaced != null) { "Replaced coordinator batch is already visible" }
            }
            hooks.beforeInstall?.invoke()
            owner.bridge.commitProjection(cut.transition) { installed = true }
            hooks.afterInstall?.invoke()
            if (cut.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(feed.seatId)
            onInstalled?.invoke()
            owner.signalDelivery()
        } catch (ex: Exception) {
            if (!installed) {
                enqueued.forEach { owner.removeOwnedBatch(feed, it) }
                replaced?.let(feed.queue::addFirst)
                onRollback?.invoke()
            }
            onFailure(ex)
        }
    }
}
