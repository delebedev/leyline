package leyline.bridge.handoff

import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId

/** Immutable targeting candidate handed to projection materialization. */
sealed interface TargetingCandidateValue {
    val optionIndex: Int

    data class Card(
        override val optionIndex: Int,
        val forgeCardId: ForgeCardId,
        val zoneId: Int,
    ) : TargetingCandidateValue

    data class Player(
        override val optionIndex: Int,
        val seatId: SeatId,
    ) : TargetingCandidateValue
}

/** Projection-ready value for one route-bound SelectTargets window. */
data class TargetingWindowValue(
    val sourceForgeCardId: ForgeCardId?,
    val sourceGrpId: Int,
    val outerAbilityGrpId: Int,
    val targetingAbilityGrpId: Int,
    val targetSourceZoneId: Int,
    val targetPromptId: Int?,
    val targetIndex: Int,
    val minTargets: Int,
    val maxTargets: Int,
    val chooserSeatId: SeatId,
    val candidates: List<TargetingCandidateValue>,
    val isTriggeredAbility: Boolean,
    val forgeAbilityId: Int,
) {
    init {
        require(targetIndex > 0)
        require(minTargets >= 0)
        require(maxTargets >= minTargets)
    }
}

/** One immutable client tap. `selected=false` is the wire Unselect action. */
data class TargetToggleValue(
    val instanceId: Int,
    val selected: Boolean,
)

/** Session-visible identity of the latest committed targeting request. */
data class PublishedTargetingInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val targetIndex: Int,
)

/** Result of one command accepted by the targeting interaction owner. */
data class TargetingCommandReceipt(
    val interactionId: String,
    val deliveryToken: Long?,
    val completed: Boolean,
    val engineWillResume: Boolean,
)

/** Engine-thread owner for route-bound targeting prompts. */
interface TargetingInteractionRuntime {
    fun awaitTargeting(
        request: PromptRequest,
        targetingAbility: SpellAbility?,
        abilityIdentity: ResolvedAbilityIdentity?,
        timeoutMs: Long?,
    ): List<Int>
}

/** Internal control result: the exact targeting deadline retired the window before any response won. */
internal class TargetingInteractionTimeoutException : RuntimeException("Targeting interaction timed out")
