package leyline.game.state

import leyline.bridge.handoff.ActionToken
import leyline.game.bundle.CastingTimeOptionsBuilder
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

data class HybridCastCostFacts(
    val cardName: String,
    val promptColors: List<ManaColor>,
    val paymentColors: List<ManaColor>,
    val manaCost: List<CastingTimeOptionsBuilder.ManaRequirementSpec>,
)

data class OptionalCastCostEntry(
    val type: CastingTimeOptionType,
    val abilityGrpId: Int,
    val keywordName: String? = null,
)

data class OptionalCastCostFacts(
    val cardName: String,
    val baseManaCost: List<Pair<ManaColor, Int>>,
    val entries: List<OptionalCastCostEntry>,
)

data class AlternateCastCostFacts(
    val cardName: String,
    val optionPromptIds: List<Int>,
    val optionCount: Int,
)

data class AlternateCastCommands(
    val defaultToken: ActionToken,
    val tokensByCtoId: Map<Int, ActionToken>,
)
