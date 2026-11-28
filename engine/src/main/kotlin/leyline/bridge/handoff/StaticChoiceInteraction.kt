package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.StaticList

data class StaticChoiceOptionValue(
    val originalOptionIndex: Int,
    val protocolValue: Int,
    val label: String? = null,
    /** Value recorded in the window's result list when this option is chosen; null records nothing. */
    val resultValue: Int? = null,
)

/** Immutable materialization input for one static enum SelectN window. */
data class StaticChoiceWindowValue(
    val kind: StaticChoiceKind,
    val staticList: StaticList?,
    /** Enum list the choice result is recorded against: [staticList], or a labelled choice's result list. */
    val resultList: StaticList? = staticList,
    val sourceForgeAbilityId: Int = 0,
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
