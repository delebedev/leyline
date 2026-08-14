package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

data class CardSelectCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
)

/** Immutable materialization input for one card-backed SelectN window. */
data class CardSelectWindowValue(
    val kind: CardSelectKind,
    val candidates: List<CardSelectCandidateValue>,
    val sourceForgeCardId: ForgeCardId?,
    val min: Int,
    val max: Int,
    val defaultOptionIndex: Int,
    val choiceResultSentiment: Int?,
)

data class PublishedCardSelectInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: CardSelectKind,
)
