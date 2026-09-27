package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.StaticList

data class StaticChoiceOptionValue(
    val originalOptionIndex: Int,
    val protocolValue: Int,
)

/** Immutable materialization input for one static enum SelectN window. */
data class StaticChoiceWindowValue(
    val kind: StaticChoiceKind,
    val staticList: StaticList,
    val options: List<StaticChoiceOptionValue>,
    val sourceForgeCardId: ForgeCardId?,
    val min: Int,
    val max: Int,
    val defaultOptionIndex: Int,
)

data class PublishedStaticChoiceInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: StaticChoiceKind,
)
