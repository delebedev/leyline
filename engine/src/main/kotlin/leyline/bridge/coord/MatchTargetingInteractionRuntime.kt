package leyline.bridge.coord

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedTargetingInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingInteractionKind
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.handoff.TargetingInteractionTimeoutException
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.game.bundle.TargetingWindowMaterializer
import leyline.game.snapshot.BoundCard
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
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

    private val nextDeliveryToken = AtomicLong()
    private var window: TargetingWindow? = null
    private var duplicateSubmitGameStateId: Int? = null

    override fun awaitTargeting(
        request: PromptRequest,
        targetingAbility: SpellAbility?,
        abilityIdentity: ResolvedAbilityIdentity?,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.Targeting)
        val value = capture.capture(request, targetingAbility, abilityIdentity)
        return awaitCaptured(
            value,
            targetingAbility,
            capture.captureTransientSourceCard(value, targetingAbility),
            timeoutMs,
            TargetingInteractionKind.Targeting,
        )
    }

    /** Shared SelectTargets lifecycle for residual card choices. */
    internal fun awaitCompatibility(
        request: PromptRequest,
        candidateHandles: List<forge.game.card.Card>,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.CompatibilityCostSelection)
        require(candidateHandles.size == request.options.size) { "Compatibility candidates must match prompt options" }
        require(request.candidateRefs.all { ref -> candidateHandles.getOrNull(ref.index)?.id == ref.entityId }) {
            "Compatibility candidate handles no longer match the frozen prompt"
        }
        val value = capture.capture(request, targetingAbility = null, abilityIdentity = null)
        return awaitCaptured(
            value,
            targetingAbility = null,
            transientSourceCard = capture.captureTransientSourceCard(value, targetingAbility = null),
            timeoutMs,
            TargetingInteractionKind.CompatibilityCostSelection,
        )
    }

    private fun awaitCaptured(
        value: TargetingWindowValue,
        targetingAbility: SpellAbility?,
        transientSourceCard: BoundCard?,
        timeoutMs: Long?,
        kind: TargetingInteractionKind,
    ): List<Int> {
        val pending = publishInitial(value, targetingAbility, transientSourceCard, timeoutMs, kind)
        return awaitCommands(pending)
    }

    fun current(): PublishedTargetingInteraction? = synchronized(owner.feedLock) { window?.published }

    /** Read-only Forge context for the in-process decision policy. */
    internal fun aiContext(): SpellAbility? = synchronized(owner.feedLock) { window?.targetingAbility }

    fun submitToggle(
        interactionId: String,
        gameStateId: Int,
        targetIndex: Int,
        toggles: List<TargetToggleValue>,
    ): TargetingCommandReceipt? = submit(TargetingCommand.Toggle(interactionId, gameStateId, targetIndex, toggles.toList()))

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
        return submit(TargetingCommand.Submit(interactionId, gameStateId))
    }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): TargetingCommandReceipt? = submit(TargetingCommand.Cancel(interactionId, gameStateId))

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
            pending.commands.offer(TargetingCommand.Terminal(cause))
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
        transientSourceCard: BoundCard?,
        timeoutMs: Long?,
        kind: TargetingInteractionKind = TargetingInteractionKind.Targeting,
    ): TargetingWindow {
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
                                feed.builder.prepareTargetingWindow(game, owner.counter, value, transientSourceCard)
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
                                kind,
                            )
                        val created =
                            TargetingWindow(
                                interactionId = published.interactionId,
                                value = value,
                                targetingAbility = targetingAbility,
                                entitiesByOptionIndex = capture.resolveEntities(value),
                                stackAbilitiesByOptionIndex = capture.resolveStackAbilities(value),
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

    private fun awaitCommands(pending: TargetingWindow): List<Int> {
        while (true) {
            val command = poll(pending)
            when (command) {
                is TargetingCommand.Toggle -> {
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
                is TargetingCommand.Submit -> {
                    publishSubmit(pending, command, duplicateDone = false)
                    return if (pending.selectedOptionIndices.isEmpty() && pending.value.finishOptionIndex != null) {
                        listOf(checkNotNull(pending.value.finishOptionIndex))
                    } else {
                        pending.selectedOptionIndices.toList()
                    }
                }
                is TargetingCommand.Cancel -> {
                    completeWithoutPublication(pending, command)
                    return emptyList()
                }
                is TargetingCommand.Terminal -> throw command.cause
            }
        }
    }

    private fun applyToggles(
        pending: TargetingWindow,
        command: TargetingCommand.Toggle,
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
        pending: TargetingWindow,
        command: TargetingCommand.Toggle,
    ) {
        val selected = pending.selectedOptionIndices.toSet()
        val legal =
            capture.legalOptions(
                pending.value,
                pending.targetingAbility,
                pending.entitiesByOptionIndex,
                pending.stackAbilitiesByOptionIndex,
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
        pending: TargetingWindow,
        command: TargetingCommand,
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
        pending: TargetingWindow,
        command: TargetingCommand,
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
        pending: TargetingWindow,
        command: TargetingCommand,
        completed: Boolean,
    ) {
        val delivery = TargetingDelivery(nextDeliveryToken.incrementAndGet(), CompletableFuture(), CompletableFuture())
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
        pending: TargetingWindow,
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

    private fun poll(pending: TargetingWindow): TargetingCommand {
        val deadline = pending.deadlineNanos ?: return pending.commands.take()
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return claimTimedOutWindowOrCommand(pending)
        return pending.commands.poll(remaining, TimeUnit.NANOSECONDS)
            ?: claimTimedOutWindowOrCommand(pending)
    }

    private fun claimTimedOutWindowOrCommand(pending: TargetingWindow): TargetingCommand {
        beforeTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            if (window === pending && pending.commandInFlight) {
                return@synchronized checkNotNull(pending.commands.poll())
            }
            if (window === pending) window = null
            throw TargetingInteractionTimeoutException()
        }
    }

    private fun submit(command: TargetingCommand): TargetingCommandReceipt? {
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
    ): TargetingWindow? {
        val pending = window ?: return null
        if (pending.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        if (requireIdle && pending.commandInFlight) return null
        return pending
    }

    private fun publishPrepared(
        feed: MatchCutCoordinator.ViewerFeed,
        prepared: TargetingWindowMaterializer.Prepared,
    ) = owner.cutInstaller.install(
        feed,
        PreparedCut(prepared.bundle.messages, prepared.transition, prepared.closesPlaybackFrame),
        CutInstallHooks(beforeInstall = beforeInstall),
    ) { ex -> owner.fail(ex) }

    private fun commandInteractionId(command: TargetingCommand): String =
        when (command) {
            is TargetingCommand.Toggle -> command.interactionId
            is TargetingCommand.Submit -> command.interactionId
            is TargetingCommand.Cancel -> command.interactionId
            is TargetingCommand.Terminal -> "terminal"
        }

    private fun commandGameStateId(command: TargetingCommand): Int =
        when (command) {
            is TargetingCommand.Toggle -> command.gameStateId
            is TargetingCommand.Submit -> command.gameStateId
            is TargetingCommand.Cancel -> command.gameStateId
            is TargetingCommand.Terminal -> 0
        }
}
