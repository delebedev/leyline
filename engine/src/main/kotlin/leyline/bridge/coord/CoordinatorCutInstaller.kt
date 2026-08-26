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
    val viewerOutputs: List<PreparedViewerOutput> = emptyList(),
    val playbackOwnerSeatId: leyline.bridge.types.SeatId? = null,
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

        fun prepareForViewers(
            prior: ProjectionState,
            planner: LogicalSequencePlanner,
            outputs: List<PreparedViewerOutput>,
            projection: ProjectionTransition?,
            closesPlaybackFrame: Boolean,
            playbackOwnerSeatId: leyline.bridge.types.SeatId? = null,
        ): PreparedCut {
            require(outputs.isNotEmpty()) { "A viewer cut must contain output" }
            require(outputs.map { it.seatId }.distinct().size == outputs.size) { "A viewer may appear only once per cut" }
            val messages = outputs.flatMap { it.batches.flatten() }
            return prepare(prior, planner, messages, projection, closesPlaybackFrame).copy(
                viewerOutputs = outputs,
                playbackOwnerSeatId = playbackOwnerSeatId,
            )
        }
    }
}

internal data class PreparedViewerOutput(
    val seatId: leyline.bridge.types.SeatId,
    val batches: List<List<GREToClientMessage>>,
)

/** One installed batch with the logical order assigned by its transition. */
internal data class CommittedOutputBatch(
    val ordinal: Long,
    val batchIndex: Int,
    val messages: List<GREToClientMessage>,
    val viewerIndex: Int = 0,
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
    ) = installOutputs(
        cut = cut,
        outputs = listOf(PreparedViewerOutput(feed.seatId, batches)),
        replaces = mapOf(feed.seatId to replaces).filterValues { it.isNotEmpty() },
        hooks = hooks,
        playbackOwnerSeatId = feed.seatId.takeIf { cut.closesPlaybackFrame },
        onInstalled = onInstalled,
        onRollback = onRollback,
        onFailure = onFailure,
    )

    fun install(
        cut: PreparedCut,
        hooks: CutInstallHooks = CutInstallHooks(),
        onInstalled: (() -> Unit)? = null,
        onRollback: (() -> Unit)? = null,
        onFailure: (Throwable) -> Nothing,
    ) = installOutputs(
        cut = cut,
        outputs = cut.viewerOutputs,
        replaces = emptyMap(),
        hooks = hooks,
        playbackOwnerSeatId = cut.playbackOwnerSeatId,
        onInstalled = onInstalled,
        onRollback = onRollback,
        onFailure = onFailure,
    )

    private fun installOutputs(
        cut: PreparedCut,
        outputs: List<PreparedViewerOutput>,
        replaces: Map<leyline.bridge.types.SeatId, List<GREToClientMessage>>,
        hooks: CutInstallHooks,
        playbackOwnerSeatId: leyline.bridge.types.SeatId?,
        onInstalled: (() -> Unit)?,
        onRollback: (() -> Unit)?,
        onFailure: (Throwable) -> Nothing,
    ) {
        require(outputs.isNotEmpty()) { "Installed cut must contain output" }
        val installedMessages = outputs.flatMap { it.batches.flatten() }
        check(installedMessages == cut.messages) { "Installed batches must contain the prepared cut messages in order" }
        val committedBatches =
            outputs.flatMapIndexed { viewerIndex, output ->
                val feed = owner.feed(output.seatId)
                output.batches.mapIndexed { batchIndex, messages ->
                    feed to
                        CommittedOutputBatch(
                            ordinal = cut.outputOrdinal,
                            batchIndex = batchIndex,
                            messages = messages,
                            viewerIndex = viewerIndex,
                        )
                }
            }
        val enqueued = mutableListOf<Pair<MatchCutCoordinator.ViewerFeed, CommittedOutputBatch>>()
        val replaced = mutableListOf<Pair<MatchCutCoordinator.ViewerFeed, CommittedOutputBatch>>()
        var installed = false
        try {
            hooks.beforeEnqueue?.invoke()
            committedBatches.forEach { (feed, batch) ->
                feed.beforeBatchEnqueue?.invoke(batch.batchIndex, batch.messages)
                feed.queue.add(batch)
                enqueued += feed to batch
            }
            replaces.forEach { (seatId, messages) ->
                val feed = owner.feed(seatId)
                val batch = owner.takeOwnedBatch(feed, messages)
                check(batch != null) { "Replaced coordinator batch is already visible" }
                replaced += feed to batch
            }
            hooks.beforeInstall?.invoke()
            owner.bridge.commitProjection(cut.transition) { installed = true }
            hooks.afterInstall?.invoke()
            playbackOwnerSeatId?.let(owner.bridge::acknowledgePlaybackFrame)
            onInstalled?.invoke()
            owner.signalDelivery()
        } catch (ex: Exception) {
            if (!installed) {
                enqueued.forEach { (feed, batch) -> owner.removeOwnedBatch(feed, batch) }
                replaced.asReversed().forEach { (feed, batch) -> feed.queue.addFirst(batch) }
                onRollback?.invoke()
            }
            onFailure(ex)
        }
    }
}
