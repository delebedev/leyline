package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import java.util.concurrent.CompletableFuture

data class CombatDamageCard(
    val id: ForgeCardId,
    val name: String,
    val netToughness: Int,
    val damage: Int,
)

data class DamageAssignmentPrompt(
    val attacker: CombatDamageCard,
    val blockers: List<CombatDamageCard>,
    val damageDealt: Int,
    val hasDefender: Boolean,
    val hasDeathtouch: Boolean,
    val hasTrample: Boolean,
)

data class OptionalActionPrompt(
    val hostCardId: ForgeCardId?,
    val hostCardName: String?,
    /** Force a full state snapshot before emitting mid-resolution prompts such as Madness. */
    val forceSnapshotBeforePrompt: Boolean = false,
    /** Override the OptionalActionMessage promptId for mechanic-specific Yes/No choices. */
    val customPromptId: Int? = null,
    val commanderReturn: CommanderReturnPromptContext? = null,
)

data class CommanderReturnPromptContext(
    val oldInstanceId: Int,
    val promptInstanceId: Int,
    val originZone: String,
    val destinationZone: String,
    val ownerSeatId: Int,
    val transferCategory: String,
)

data class NumericInputPrompt(
    /** The card whose ability is asking for the number. Drives sourceId resolution in the handler. */
    val sourceCardId: ForgeCardId?,
    val sourceCardName: String?,
    val min: Int,
    val max: Int,
)

class PendingDamageAssignment internal constructor(
    val prompt: DamageAssignmentPrompt,
    internal val future: CompletableFuture<MutableMap<forge.game.card.Card?, Int>?>,
)

class PendingOptionalAction internal constructor(
    val prompt: OptionalActionPrompt,
    internal val future: CompletableFuture<Boolean>,
)

class PendingNumericInput internal constructor(
    val prompt: NumericInputPrompt,
    internal val future: CompletableFuture<Int>,
)

data class DamageAssignmentValue(
    val attackerId: ForgeCardId,
    val totalDamage: Int,
    val assignments: List<AssignedDamageValue>,
)

data class AssignedDamageValue(
    /** Null identifies the defending player. */
    val targetId: ForgeCardId?,
    val damage: Int,
)
