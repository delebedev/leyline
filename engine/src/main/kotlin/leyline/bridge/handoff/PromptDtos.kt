package leyline.bridge.handoff

import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.zone.ZoneType
import java.util.concurrent.CompletableFuture

data class DamageAssignmentPrompt(
    val attacker: Card,
    val blockers: CardCollectionView,
    val damageDealt: Int,
    val defender: GameEntity?,
    val hasDeathtouch: Boolean,
    val hasTrample: Boolean,
    val future: CompletableFuture<MutableMap<Card?, Int>?>,
)

data class OptionalActionPrompt(
    val hostCard: Card?,
    val future: CompletableFuture<Boolean>,
    /** Force a full state snapshot before emitting mid-resolution prompts such as Madness. */
    val forceSnapshotBeforePrompt: Boolean = false,
    /** Override the OptionalActionMessage promptId for mechanic-specific Yes/No choices. */
    val customPromptId: Int? = null,
    val commanderReturn: CommanderReturnPromptContext? = null,
)

data class CommanderReturnPromptContext(
    val oldInstanceId: Int,
    val promptInstanceId: Int,
    val originZone: ZoneType,
    val destinationZone: ZoneType,
    val ownerSeatId: Int,
    val transferCategory: String,
)

data class NumericInputPrompt(
    /** The card whose ability is asking for the number. Drives sourceId resolution in the handler. */
    val sourceCard: Card?,
    val min: Int,
    val max: Int,
    val future: CompletableFuture<Int>,
)
