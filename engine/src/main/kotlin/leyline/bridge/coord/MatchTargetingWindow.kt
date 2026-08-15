package leyline.bridge.coord

import forge.game.GameEntity
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PublishedTargetingInteraction
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.InstanceId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

internal sealed interface TargetingCommand {
    val reply: CompletableFuture<TargetingCommandReceipt>

    data class Toggle(
        val interactionId: String,
        val gameStateId: Int,
        val targetIndex: Int,
        val toggles: List<TargetToggleValue>,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Submit(
        val interactionId: String,
        val gameStateId: Int,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Cancel(
        val interactionId: String,
        val gameStateId: Int,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Terminal(
        val cause: Throwable,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand
}

internal data class TargetingDelivery(
    val token: Long,
    val acknowledged: CompletableFuture<Unit>,
    val released: CompletableFuture<Unit>,
)

internal data class TargetingWindow(
    val interactionId: String,
    val value: TargetingWindowValue,
    val targetingAbility: SpellAbility?,
    val entitiesByOptionIndex: Map<Int, GameEntity>,
    val stackAbilitiesByOptionIndex: Map<Int, SpellAbility>,
    val instanceIdByOptionIndex: Map<Int, Int>,
    val sourceInstanceId: InstanceId?,
    val deadlineNanos: Long?,
    val commands: LinkedBlockingQueue<TargetingCommand> = LinkedBlockingQueue(),
    val selectedOptionIndices: MutableList<Int> = mutableListOf(),
    var published: PublishedTargetingInteraction,
    var commandInFlight: Boolean = false,
    var delivery: TargetingDelivery? = null,
)
