package leyline.bridge.coord

import forge.game.GameEntity
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedTargetingInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.handoff.TargetingInteractionTimeoutException
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.InstanceId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.game.bundle.TargetingWindowMaterializer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exact targeting-window lifecycle beneath [MatchCutCoordinator]. */
internal class MatchTargetingInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : TargetingInteractionRuntime {
    internal var beforeInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterCommandClaim: (() -> Unit)? = null
    internal var afterCompletedDeliveryRelease: (() -> Unit)? = null
    private val capture = TargetingWindowCapture(owner)

    private sealed interface Command {
        val reply: CompletableFuture<TargetingCommandReceipt>

        data class Toggle(
            val interactionId: String,
            val gameStateId: Int,
            val targetIndex: Int,
            val toggles: List<TargetToggleValue>,
            override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Submit(
            val interactionId: String,
            val gameStateId: Int,
            override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Cancel(
            val interactionId: String,
            val gameStateId: Int,
            override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Terminal(
            val cause: Throwable,
            override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
        ) : Command
    }

    private data class Delivery(
        val token: Long,
        val acknowledged: CompletableFuture<Unit>,
        val released: CompletableFuture<Unit>,
    )

    private data class Window(
        val interactionId: String,
        val value: TargetingWindowValue,
        val targetingAbility: SpellAbility?,
        val entitiesByOptionIndex: Map<Int, GameEntity>,
        val instanceIdByOptionIndex: Map<Int, Int>,
        val sourceInstanceId: InstanceId?,
        val deadlineNanos: Long?,
        val commands: LinkedBlockingQueue<Command> = LinkedBlockingQueue(),
        val selectedOptionIndices: MutableList<Int> = mutableListOf(),
        var published: PublishedTargetingInteraction,
        var commandInFlight: Boolean = false,
        var delivery: Delivery? = null,
    )

    private val nextDeliveryToken = AtomicLong()
    private var window: Window? = null
    private var duplicateSubmitGameStateId: Int? = null

    override fun awaitTargeting(
        request: PromptRequest,
        targetingAbility: SpellAbility?,
        abilityIdentity: ResolvedAbilityIdentity?,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.Targeting)
        val value = capture.capture(request, targetingAbility, abilityIdentity)
        val pending = publishInitial(value, targetingAbility, timeoutMs)
        return awaitCommands(pending)
    }

    fun current(): PublishedTargetingInteraction? = synchronized(owner.feedLock) { window?.published }

    fun submitToggle(
        interactionId: String,
        gameStateId: Int,
        targetIndex: Int,
        toggles: List<TargetToggleValue>,
    ): TargetingCommandReceipt? = submit(Command.Toggle(interactionId, gameStateId, targetIndex, toggles.toList()))

    fun submitTargets(
        interactionId: String?,
        gameStateId: Int,
    ): TargetingCommandReceipt? {
        if (interactionId == null) {
            return synchronized(owner.feedLock) {
                if (duplicateSubmitGameStateId == gameStateId) {
                    duplicateSubmitGameStateId = null
                    TargetingCommandReceipt("completed", null, completed = true, engineWillResume = false)
                } else {
                    null
                }
            }
        }
        return submit(Command.Submit(interactionId, gameStateId))
    }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): TargetingCommandReceipt? = submit(Command.Cancel(interactionId, gameStateId))

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

    fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            val pending = window ?: return
            pending.delivery?.acknowledged?.completeExceptionally(cause)
            pending.delivery?.released?.completeExceptionally(cause)
            pending.commands.forEach { it.reply.completeExceptionally(cause) }
            pending.commands.offer(Command.Terminal(cause))
            window = null
        }
    }

    fun reset() {
        synchronized(owner.feedLock) {
            window = null
            duplicateSubmitGameStateId = null
        }
    }

    private fun publishInitial(
        value: TargetingWindowValue,
        targetingAbility: SpellAbility?,
        timeoutMs: Long?,
    ): Window {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null) { "A targeting interaction is already pending" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val prepared =
                            try {
                                feed.builder.prepareTargetingWindow(game, owner.counter, value)
                            } catch (ex: Exception) {
                                owner.fail(ex)
                            }
                        publishPrepared(feed, prepared)
                        val projection = prepared.transition?.nextState ?: owner.bridge.projectionStateSnapshot()
                        val published =
                            PublishedTargetingInteraction(
                                UUID.randomUUID().toString(),
                                checkNotNull(prepared.bundle.actionGameStateId),
                                value.targetIndex,
                            )
                        val created =
                            Window(
                                interactionId = published.interactionId,
                                value = value,
                                targetingAbility = targetingAbility,
                                entitiesByOptionIndex = capture.resolveEntities(value),
                                instanceIdByOptionIndex = capture.resolveInstanceIds(value, projection),
                                sourceInstanceId =
                                    value.sourceForgeCardId?.let(projection.identities.forgeIdToInstanceId::get),
                                deadlineNanos = timeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) },
                                published = published,
                            )
                        window = created
                        duplicateSubmitGameStateId = null
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun awaitCommands(pending: Window): List<Int> {
        while (true) {
            val command = poll(pending)
            when (command) {
                is Command.Toggle -> {
                    applyToggles(pending, command)
                    val completesTriggered =
                        pending.value.isTriggeredAbility &&
                            pending.value.minTargets == 1 &&
                            pending.value.maxTargets == 1 &&
                            pending.selectedOptionIndices.size == 1
                    if (completesTriggered) {
                        publishSubmit(pending, command, duplicateDone = true)
                        return pending.selectedOptionIndices.toList()
                    }
                    publishRePrompt(pending, command)
                }
                is Command.Submit -> {
                    publishSubmit(pending, command, duplicateDone = false)
                    return pending.selectedOptionIndices.toList()
                }
                is Command.Cancel -> {
                    completeWithoutPublication(pending, command)
                    return emptyList()
                }
                is Command.Terminal -> throw command.cause
            }
        }
    }

    private fun applyToggles(
        pending: Window,
        command: Command.Toggle,
    ) {
        if (command.targetIndex != pending.value.targetIndex) return
        val optionByInstanceId = pending.instanceIdByOptionIndex.entries.associate { (option, iid) -> iid to option }
        command.toggles.forEach { toggle ->
            val option = optionByInstanceId[toggle.instanceId] ?: return@forEach
            if (toggle.selected) {
                if (option !in pending.selectedOptionIndices) pending.selectedOptionIndices += option
            } else {
                pending.selectedOptionIndices -= option
            }
        }
    }

    private fun publishRePrompt(
        pending: Window,
        command: Command.Toggle,
    ) {
        val selected = pending.selectedOptionIndices.toSet()
        val legal =
            capture.legalOptions(
                pending.value,
                pending.targetingAbility,
                pending.entitiesByOptionIndex,
                selected,
            )
        val prepared =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        val current =
                            matching(pending.interactionId, command.gameStateId, requireIdle = false)
                                ?: owner.fail(IllegalStateException("Targeting window changed during re-prompt"))
                        val feed = owner.feed(owner.humanSeat)
                        val value =
                            try {
                                feed.builder.prepareTargetingRePrompt(
                                    owner.counter,
                                    owner.bridge.projectionStateSnapshot(),
                                    pending.value,
                                    selected,
                                    legal,
                                )
                            } catch (ex: Exception) {
                                owner.fail(ex)
                            }
                        publishPrepared(feed, value)
                        current.published =
                            current.published.copy(gameStateId = checkNotNull(value.bundle.actionGameStateId))
                        beginDelivery(current, command, completed = false)
                        value
                    }
                }
            }
        check(prepared.bundle.messages.isNotEmpty())
        awaitDelivery(pending, completed = false)
    }

    private fun publishSubmit(
        pending: Window,
        command: Command,
        duplicateDone: Boolean,
    ) {
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    matching(pending.interactionId, commandGameStateId(command), requireIdle = false)
                        ?: owner.fail(IllegalStateException("Targeting window changed during submit"))
                    val feed = owner.feed(owner.humanSeat)
                    val prepared =
                        try {
                            feed.builder.prepareTargetingSubmit(
                                owner.counter,
                                owner.bridge.projectionStateSnapshot(),
                                pending.sourceInstanceId,
                                owner.humanSeat,
                            )
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    publishPrepared(feed, prepared)
                    if (duplicateDone) duplicateSubmitGameStateId = pending.published.gameStateId
                    beginDelivery(pending, command, completed = true)
                }
            }
        }
        awaitDelivery(pending, completed = true)
    }

    private fun completeWithoutPublication(
        pending: Window,
        command: Command,
    ) {
        synchronized(owner.feedLock) {
            matching(pending.interactionId, commandGameStateId(command), requireIdle = false)
                ?: owner.fail(IllegalStateException("Targeting window changed during completion"))
            command.reply.complete(
                TargetingCommandReceipt(
                    pending.interactionId,
                    deliveryToken = null,
                    completed = true,
                    engineWillResume = true,
                ),
            )
            window = null
        }
    }

    private fun beginDelivery(
        pending: Window,
        command: Command,
        completed: Boolean,
    ) {
        val delivery = Delivery(nextDeliveryToken.incrementAndGet(), CompletableFuture(), CompletableFuture())
        pending.delivery = delivery
        command.reply.complete(
            TargetingCommandReceipt(
                pending.interactionId,
                delivery.token,
                completed,
                engineWillResume = completed,
            ),
        )
    }

    private fun awaitDelivery(
        pending: Window,
        completed: Boolean,
    ) {
        val delivery = checkNotNull(pending.delivery)
        try {
            delivery.acknowledged.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        } finally {
            synchronized(owner.feedLock) {
                pending.delivery = null
                pending.commandInFlight = false
                if (completed && window === pending) window = null
                delivery.released.complete(Unit)
            }
            if (completed) afterCompletedDeliveryRelease?.invoke()
        }
    }

    private fun poll(pending: Window): Command {
        val deadline = pending.deadlineNanos ?: return pending.commands.take()
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return claimTimedOutWindowOrCommand(pending)
        return pending.commands.poll(remaining, TimeUnit.NANOSECONDS)
            ?: claimTimedOutWindowOrCommand(pending)
    }

    private fun claimTimedOutWindowOrCommand(pending: Window): Command {
        beforeTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            if (window === pending && pending.commandInFlight) {
                return@synchronized checkNotNull(pending.commands.poll())
            }
            if (window === pending) window = null
            throw TargetingInteractionTimeoutException()
        }
    }

    private fun submit(command: Command): TargetingCommandReceipt? {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = matching(commandInteractionId(command), commandGameStateId(command)) ?: return null
            pending.commandInFlight = true
            pending.commands.add(command)
            afterCommandClaim?.invoke()
        }
        return try {
            command.reply.get()
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }
    }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
        requireIdle: Boolean = true,
    ): Window? {
        val pending = window ?: return null
        if (pending.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        if (requireIdle && pending.commandInFlight) return null
        return pending
    }

    private fun publishPrepared(
        feed: MatchCutCoordinator.ViewerFeed,
        prepared: TargetingWindowMaterializer.Prepared,
    ) {
        val batch = prepared.bundle.messages
        var enqueued = false
        var installed = false
        try {
            feed.queue.add(batch)
            enqueued = true
            beforeInstall?.invoke()
            prepared.transition?.let { transition ->
                owner.bridge.commitProjection(transition) { installed = true }
            } ?: run { installed = true }
            if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(feed.seatId)
        } catch (ex: Exception) {
            if (!installed && enqueued) owner.removeOwnedBatch(feed, batch)
            owner.fail(ex)
        }
    }

    private fun commandInteractionId(command: Command): String =
        when (command) {
            is Command.Toggle -> command.interactionId
            is Command.Submit -> command.interactionId
            is Command.Cancel -> command.interactionId
            is Command.Terminal -> "terminal"
        }

    private fun commandGameStateId(command: Command): Int =
        when (command) {
            is Command.Toggle -> command.gameStateId
            is Command.Submit -> command.gameStateId
            is Command.Cancel -> command.gameStateId
            is Command.Terminal -> 0
        }
}
