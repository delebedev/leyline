package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.FinalManaSourcePaymentValue
import leyline.bridge.handoff.ManaSourcePaymentCommandReceipt
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.ManaSourcePaymentResult
import leyline.bridge.handoff.ManaSourcePaymentRuntime
import leyline.bridge.handoff.ManaSourcePaymentShardValue
import leyline.bridge.handoff.ManaSourcePaymentTimeoutException
import leyline.bridge.handoff.ManaSourcePaymentWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedManaSourcePaymentInteraction
import leyline.game.ManaSourcePaymentMaterializationDiagnostic
import leyline.game.PendingManaSourcePaymentCut
import leyline.game.PlaybackTerminalFailure
import leyline.game.data.KeywordAbilityIds
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exact iterative mana-source payment lifecycle beneath [MatchCutCoordinator]. */
internal class MatchManaSourcePaymentRuntime(
    private val owner: MatchCutCoordinator,
) : ManaSourcePaymentRuntime {
    private sealed interface Command {
        val reply: CompletableFuture<ManaSourcePaymentCommandReceipt>

        data class Select(
            val interactionId: String,
            val gameStateId: Int,
            val optionIndices: List<Int>,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Complete(
            val interactionId: String,
            val gameStateId: Int,
            val optionIndices: List<Int>,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Cancel(
            val interactionId: String,
            val gameStateId: Int,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Terminal(
            val cause: Throwable,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command
    }

    private data class Delivery(
        val token: Long,
        val acknowledged: CompletableFuture<Unit> = CompletableFuture(),
        val released: CompletableFuture<Unit> = CompletableFuture(),
    )

    private data class Window(
        val interactionId: String,
        val handlesByOption: Map<Int, Card>,
        val deadlineNanos: Long?,
        val commands: LinkedBlockingQueue<Command> = LinkedBlockingQueue(),
        var value: ManaSourcePaymentWindowValue,
        var published: PublishedManaSourcePaymentInteraction,
        var cut: PendingManaSourcePaymentCut,
        var optionByInstanceId: Map<Int, Int>,
        var commandInFlight: Command? = null,
        var delivery: Delivery? = null,
    )

    private data class Publication(
        val published: PublishedManaSourcePaymentInteraction,
        val cut: PendingManaSourcePaymentCut,
        val optionByInstanceId: Map<Int, Int>,
    )

    private val capture = ManaSourcePaymentWindowCapture(owner)
    private val nextDeliveryToken = AtomicLong()
    private var window: Window? = null

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var afterCommandEnqueue: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null
    internal var beforeDeliveryRelease: (() -> Unit)? = null

    override fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): ManaSourcePaymentResult {
        val initial =
            try {
                capture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.failManaSourcePayment(ex)
            }
        val interactionId = UUID.randomUUID().toString()
        lateinit var pending: Window
        publish(interactionId, initial.value) { publication ->
            check(window == null) { "A mana-source payment is already pending" }
            pending =
                Window(
                    interactionId = interactionId,
                    handlesByOption = initial.handlesByOption,
                    deadlineNanos = timeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) },
                    value = initial.value,
                    published = publication.published,
                    cut = publication.cut,
                    optionByInstanceId = publication.optionByInstanceId,
                )
            window = pending
        }
        owner.bridge.prioritySignal.signal()
        return try {
            awaitCommands(pending)
        } catch (ex: ManaSourcePaymentTimeoutException) {
            throw ex
        } catch (ex: PlaybackTerminalFailure) {
            throw ex
        } catch (ex: Exception) {
            owner.failManaSourcePayment(ex, pending.cut)
        }
    }

    override fun recordFinalPayment(value: FinalManaSourcePaymentValue) {
        if (value.kind == ManaSourcePaymentKind.Waterbend) return
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            if (value.payments.isEmpty()) {
                owner.bridge
                    .promptBridge(owner.humanSeat)
                    .journal
                    .clearConvokePayments(value.sourceForgeCardId)
            } else {
                recordPaymentFacts(value)
            }
        }
    }

    fun current(): PublishedManaSourcePaymentInteraction? =
        synchronized(owner.feedLock) { window?.takeUnless { it.commandInFlight != null || it.delivery != null }?.published }

    fun select(
        interactionId: String,
        gameStateId: Int,
        instanceIds: List<Int>,
    ): ManaSourcePaymentCommandReceipt? = submitSelection(interactionId, gameStateId, instanceIds, complete = false)

    fun complete(
        interactionId: String,
        gameStateId: Int,
        instanceIds: List<Int>,
    ): ManaSourcePaymentCommandReceipt? = submitSelection(interactionId, gameStateId, instanceIds, complete = true)

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): ManaSourcePaymentCommandReceipt? = submit(Command.Cancel(interactionId, gameStateId))

    fun acknowledgeDelivery(
        interactionId: String,
        token: Long,
    ): Boolean {
        val delivery =
            synchronized(owner.feedLock) {
                val pending = window ?: return@synchronized null
                if (pending.interactionId != interactionId || pending.delivery?.token != token) return@synchronized null
                pending.delivery?.also { it.acknowledged.complete(Unit) }
            } ?: return false
        return try {
            delivery.released.get()
            true
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }
    }

    fun pendingCutLocked(): PendingManaSourcePaymentCut? = window?.cut.also { afterDeliveryCutLookup?.invoke() }

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            val pending = window ?: return
            pending.delivery?.acknowledged?.completeExceptionally(cause)
            pending.delivery?.released?.completeExceptionally(cause)
            pending.commandInFlight?.reply?.completeExceptionally(cause)
            pending.commands.forEach { it.reply.completeExceptionally(cause) }
            pending.commands.offer(Command.Terminal(cause))
            window = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) { window = null }
    }

    private fun submitSelection(
        interactionId: String,
        gameStateId: Int,
        instanceIds: List<Int>,
        complete: Boolean,
    ): ManaSourcePaymentCommandReceipt? {
        if ((!complete && instanceIds.isEmpty()) || instanceIds.size != instanceIds.distinct().size) return null
        val command =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val pending = matching(interactionId, gameStateId) ?: return@synchronized null
                val options = instanceIds.map { pending.optionByInstanceId[it] ?: return@synchronized null }
                if (pending.value.selections.size + options.size > pending.value.maxSelection) return@synchronized null
                if (!canPayFrozenShards(pending.value, options)) return@synchronized null
                if (complete) Command.Complete(interactionId, gameStateId, options) else Command.Select(interactionId, gameStateId, options)
            } ?: return null
        return submit(command)
    }

    private fun submit(command: Command): ManaSourcePaymentCommandReceipt? {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(command) ?: return null
            pending.commandInFlight = command
            pending.commands.offer(command)
            afterCommandEnqueue?.invoke()
        }
        return try {
            command.reply.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }
    }

    private fun awaitCommands(pending: Window): ManaSourcePaymentResult {
        while (true) {
            val command = nextCommand(pending)
            when (command) {
                is Command.Select -> handleSelection(pending, command)
                is Command.Complete -> {
                    val value = capture.select(pending.value, pending.handlesByOption, command.optionIndices)
                    return complete(pending, command, value, value.selections.map { it.originalOptionIndex })
                }
                is Command.Cancel ->
                    return complete(
                        pending,
                        command,
                        pending.value,
                        pending.value.selections.map { it.originalOptionIndex },
                    )
                is Command.Terminal -> throw command.cause
            }
        }
    }

    private fun handleSelection(
        pending: Window,
        command: Command.Select,
    ) {
        val next = capture.select(pending.value, pending.handlesByOption, command.optionIndices)
        val delivery = Delivery(nextDeliveryToken.incrementAndGet())
        publish(
            interactionId = pending.interactionId,
            value = next,
            beforePrepare = { recordPaymentFacts(next) },
        ) { publication ->
            check(window === pending && pending.commandInFlight === command)
            pending.value = next
            pending.published = publication.published
            pending.cut = publication.cut
            pending.optionByInstanceId = publication.optionByInstanceId
            pending.delivery = delivery
            command.reply.complete(ManaSourcePaymentCommandReceipt(pending.interactionId, completed = false, delivery.token))
        }
        try {
            delivery.acknowledged.get()
            beforeDeliveryRelease?.invoke()
            synchronized(owner.feedLock) {
                if (window === pending) {
                    pending.delivery = null
                    pending.commandInFlight = null
                }
                delivery.released.complete(Unit)
            }
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }
    }

    private fun complete(
        pending: Window,
        command: Command,
        finalValue: ManaSourcePaymentWindowValue,
        selectedOptions: List<Int>,
    ): ManaSourcePaymentResult {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            check(window === pending && pending.commandInFlight === command)
            recordPaymentFacts(finalValue)
            window = null
            command.reply.complete(ManaSourcePaymentCommandReceipt(pending.interactionId, completed = true))
        }
        return ManaSourcePaymentResult(
            selectedOptions,
            finalValue.selections.map { ManaSourcePaymentShardValue(it.originalOptionIndex, it.costColor) },
        )
    }

    private fun publish(
        interactionId: String,
        value: ManaSourcePaymentWindowValue,
        beforePrepare: () -> Unit = {},
        onPublished: (Publication) -> Unit,
    ): Publication =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val feed = owner.feed(owner.humanSeat)
                    val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                    val diagnostic = ManaSourcePaymentMaterializationDiagnostic(interactionId, value)
                    val prepared =
                        try {
                            beforePrepare()
                            feed.builder.prepareManaSourcePayment(game, owner.counter, value)
                        } catch (ex: Exception) {
                            owner.failManaSourcePayment(ex, diagnostic = diagnostic)
                        }
                    val published =
                        PublishedManaSourcePaymentInteraction(
                            interactionId,
                            checkNotNull(prepared.bundle.actionGameStateId),
                            value.kind,
                        )
                    val exact =
                        PendingManaSourcePaymentCut(
                            interactionId,
                            published.gameStateId,
                            value,
                            prepared.bundle.messages,
                            prepared.transition,
                        )
                    val projection = prepared.transition.nextState
                    val optionEntries =
                        value.candidates.map { candidate ->
                            val instanceId =
                                projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                                    ?: owner.failManaSourcePayment(
                                        IllegalStateException("Mana-source candidate ${candidate.forgeCardId.value} was not projected"),
                                        exact,
                                    )
                            instanceId to candidate.originalOptionIndex
                        }
                    val optionByInstanceId = optionEntries.toMap()
                    if (optionByInstanceId.size != optionEntries.size) {
                        owner.failManaSourcePayment(IllegalStateException("Mana-source candidates have ambiguous client identities"), exact)
                    }
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
                        Publication(published, exact, optionByInstanceId).also(onPublished)
                    } catch (ex: Exception) {
                        if (!installed && enqueued) owner.removeOwnedBatch(feed, batch)
                        owner.failManaSourcePayment(ex, exact)
                    }
                }
            }
        }

    private fun nextCommand(pending: Window): Command {
        val command =
            if (pending.deadlineNanos == null) {
                pending.commands.take()
            } else {
                val deadline = pending.deadlineNanos
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) null else pending.commands.poll(remaining, TimeUnit.NANOSECONDS)
            }
        if (command != null) return command
        beforeTimeoutClaim?.invoke()
        synchronized(owner.feedLock) {
            if (window === pending && pending.commands.isEmpty()) {
                window = null
                throw ManaSourcePaymentTimeoutException()
            }
        }
        return pending.commands.take()
    }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
    ): Window? {
        val pending = window ?: return null
        if (pending.commandInFlight != null || pending.delivery != null) return null
        if (pending.published.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        return pending
    }

    private fun matching(command: Command): Window? =
        when (command) {
            is Command.Select -> matching(command.interactionId, command.gameStateId)
            is Command.Complete -> matching(command.interactionId, command.gameStateId)
            is Command.Cancel -> matching(command.interactionId, command.gameStateId)
            is Command.Terminal -> null
        }

    private fun canPayFrozenShards(
        value: ManaSourcePaymentWindowValue,
        options: List<Int>,
    ): Boolean {
        val candidates = value.candidates.associateBy { it.originalOptionIndex }
        val requested = options.map { candidates[it]?.costColor ?: return false }.groupingBy { it }.eachCount()
        val available = value.manaCost.toMap()
        return requested.all { (color, count) -> count <= available.getOrDefault(color, 0) }
    }

    private fun recordPaymentFacts(value: ManaSourcePaymentWindowValue) {
        if (value.kind == ManaSourcePaymentKind.Waterbend || value.selections.isEmpty()) return
        val source = value.sourceForgeCardId ?: return
        val substitutionGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE
        val paymentAbilityGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE_PAYMENT
        owner.bridge
            .promptBridge(owner.humanSeat)
            .journal
            .record(
                PromptSideEffect.ConvokePayments(
                    sourceForgeCardId = source,
                    payments =
                        value.selections.map {
                            PromptSideEffect.ConvokePayment(
                                paymentForgeCardId = it.forgeCardId,
                                color = it.paymentColor.number,
                                substitutionGrpId = substitutionGrpId,
                                paymentAbilityGrpId = paymentAbilityGrpId,
                            )
                        },
                ),
            )
    }

    private fun recordPaymentFacts(value: FinalManaSourcePaymentValue) {
        val substitutionGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE
        val paymentAbilityGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE_PAYMENT
        owner.bridge
            .promptBridge(owner.humanSeat)
            .journal
            .record(
                PromptSideEffect.ConvokePayments(
                    sourceForgeCardId = value.sourceForgeCardId,
                    payments =
                        value.payments.map {
                            PromptSideEffect.ConvokePayment(
                                paymentForgeCardId = it.paymentForgeCardId,
                                color = it.paymentColor.number,
                                substitutionGrpId = substitutionGrpId,
                                paymentAbilityGrpId = paymentAbilityGrpId,
                            )
                        },
                ),
            )
    }
}
