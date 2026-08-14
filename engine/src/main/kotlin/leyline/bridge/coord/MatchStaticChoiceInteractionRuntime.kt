package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.handoff.StaticChoiceInteractionTimeoutException
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.game.PendingStaticChoiceCut
import leyline.game.StaticChoiceMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact static enum SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchStaticChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : StaticChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedStaticChoiceInteraction,
        val value: StaticChoiceWindowValue,
        val cut: PendingStaticChoiceCut,
        val optionByValue: Map<Int, Int>,
        val future: CompletableFuture<List<Int>> = CompletableFuture(),
    )

    private var window: Window? = null

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeResponseComplete: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    override fun awaitSelection(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int> {
        val initial =
            try {
                StaticChoiceWindowCapture.initial(request)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedStaticChoiceInteraction? = synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone }?.published }

    fun submit(
        interactionId: String,
        gameStateId: Int,
        selectedValues: List<Int>,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(interactionId, gameStateId) ?: return false
            if (selectedValues.size !in pending.value.min..pending.value.max) return false
            if (selectedValues.size != selectedValues.distinct().size) return false
            val options = selectedValues.map { pending.optionByValue[it] ?: return false }
            recordChoiceResults(pending, selectedValues)
            beforeResponseComplete?.invoke()
            window = null
            pending.future.complete(options)
        }

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) { window = null }
    }

    internal fun pendingCutLocked(): PendingStaticChoiceCut? =
        window
            ?.takeUnless { it.future.isDone }
            ?.cut
            .also { afterDeliveryCutLookup?.invoke() }

    private fun publish(initial: StaticChoiceWindowValue): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null) { "A StaticChoice interaction is already pending" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val interactionId = UUID.randomUUID().toString()
                        val diagnostic = StaticChoiceMaterializationDiagnostic(interactionId, initial)
                        val prepared =
                            try {
                                feed.builder.prepareStaticChoiceWindow(game, owner.counter, initial)
                            } catch (ex: Exception) {
                                owner.failStaticChoice(ex, diagnostic = diagnostic)
                            }
                        val published =
                            PublishedStaticChoiceInteraction(
                                interactionId,
                                checkNotNull(prepared.bundle.actionGameStateId),
                                initial.kind,
                            )
                        val exact =
                            PendingStaticChoiceCut(
                                interactionId,
                                published.gameStateId,
                                initial,
                                prepared.bundle.messages,
                                prepared.transition,
                            )
                        val optionByValue = initial.options.associate { it.protocolValue to it.originalOptionIndex }
                        val created = Window(published, initial, exact, optionByValue)
                        publishPrepared(feed, prepared, exact)
                        window = created
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun publishPrepared(
        feed: MatchCutCoordinator.ViewerFeed,
        prepared: leyline.game.bundle.StaticChoiceWindowMaterializer.Prepared,
        exact: PendingStaticChoiceCut,
    ) {
        val batch = prepared.bundle.messages
        var enqueued = false
        var installed = false
        try {
            feed.beforeBatchEnqueue?.invoke(0, batch)
            feed.queue.add(batch)
            enqueued = true
            beforeInstall?.invoke()
            owner.bridge.commitProjection(prepared.transition) { installed = true }
            afterInstall?.invoke()
            if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(owner.humanSeat)
        } catch (ex: Exception) {
            if (!installed && enqueued) owner.removeOwnedBatch(feed, batch)
            owner.failStaticChoice(ex, exact)
        }
    }

    private fun recordChoiceResults(
        pending: Window,
        selectedValues: List<Int>,
    ) {
        val source = pending.value.sourceForgeCardId ?: return
        selectedValues.forEach { value ->
            owner.bridge
                .seat(owner.humanSeat)
                .prompt.journal
                .record(
                    PromptSideEffect.ChoiceResult(
                        sourceForgeCardId = source,
                        chooserSeatId = owner.humanSeat,
                        choiceValue = value,
                        choiceDomain = pending.value.kind.choiceDomain(),
                        sentiment = 2,
                    ),
                )
        }
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): List<Int> =
        try {
            if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (window === pending && !pending.future.isDone) {
                    window = null
                    pending.future.completeExceptionally(StaticChoiceInteractionTimeoutException())
                }
            }
            completedValue(pending)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun completedValue(pending: Window): List<Int> =
        try {
            pending.future.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
    ): Window? {
        val pending = window ?: return null
        if (pending.future.isDone) return null
        if (pending.published.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        return pending
    }

    private fun StaticChoiceKind.choiceDomain(): Int =
        when (this) {
            StaticChoiceKind.Color -> StaticList.Colors.number
            StaticChoiceKind.Subtype -> StaticList.SubTypes.number
            StaticChoiceKind.Parity -> StaticList.Parities.number
        }
}
