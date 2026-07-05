package leyline.acceptance

data class AcceptanceSuite(
    val name: String,
    val description: String?,
    val scenarios: List<AcceptanceScenario>,
)

data class AcceptanceScenario(
    val id: String,
    val puzzle: String,
    val run: String?,
    val expect: String?,
    val steps: List<AcceptanceStep>,
)

sealed interface AcceptanceStep {
    val label: String
}

data class WaitStep(
    val conditions: List<AcceptanceCondition>,
) : AcceptanceStep {
    override val label: String = "wait"
}

data class ExpectStep(
    val conditions: List<AcceptanceCondition>,
) : AcceptanceStep {
    override val label: String = "expect"
}

data class PassUntilStep(
    val conditions: List<AcceptanceCondition>,
    val maxPasses: Int = 20,
) : AcceptanceStep {
    override val label: String = "pass_until"
}

data class ActivateStep(
    val card: String,
    val zone: AcceptanceZone = AcceptanceZone.Battlefield,
    val abilityIndex: Int = 0,
) : AcceptanceStep {
    override val label: String = "activate $card"
}

data class ChooseStep(
    val optionalCost: AcceptanceCastingTimeOption?,
    val ctoId: Int?,
) : AcceptanceStep {
    override val label: String = optionalCost?.let { "choose ${it.yamlName}" } ?: "choose cto $ctoId"
}

data class ManaTypeChoicesStep(
    val choices: List<AcceptanceManaTypeChoice>,
) : AcceptanceStep {
    override val label: String = "mana_type_choices ${choices.joinToString { it.yamlName }}"
}

data class ModalChoiceStep(
    val index: Int,
) : AcceptanceStep {
    override val label: String = "modal_choice $index"
}

data class StaticChoiceStep(
    val id: Int,
) : AcceptanceStep {
    override val label: String = "static_choice $id"
}

data class OptionalActionStep(
    val accept: Boolean,
) : AcceptanceStep {
    override val label: String = if (accept) "optional_action accept" else "optional_action decline"
}

data class TargetStep(
    val target: AcceptanceTargetSpec,
) : AcceptanceStep {
    override val label: String = "target ${target.label}"
}

data class BlockStep(
    val blocker: String,
    val attacker: String,
) : AcceptanceStep {
    override val label: String = "block $attacker with $blocker"
}

data class AttackStep(
    val cards: List<String>,
    val altCost: AcceptanceAltCost? = null,
    val target: AcceptanceTargetSpec? = null,
) : AcceptanceStep {
    override val label: String = "attack ${cards.joinToString()}${target?.let { " at ${it.label}" } ?: ""}"
}

data class TurnFaceUpStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "turn_face_up $card"
}

data class PlayLandStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "play_land $card"
}

data class CastStep(
    val card: String,
    val zone: AcceptanceZone = AcceptanceZone.Hand,
    val altCost: AcceptanceAltCost? = null,
) : AcceptanceStep {
    override val label: String = "cast $card"
}

data class SelectCostStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val zone: AcceptanceZone,
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "select_cost ${cards.joinToString()}"
}

data class SelectCardStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceStep {
    override val label: String = "select_card $card"
}

data class SelectCardsStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val zone: AcceptanceZone,
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "select_cards ${cards.joinToString()}"
}

data class SearchCardsStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "search_cards ${cards.joinToString()}"
}

data class OrderCardsStep(
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "order_cards ${cards.joinToString()}"
}

data object ResolveStackStep : AcceptanceStep {
    override val label: String = "resolve_stack"
}

data object AttackAllStep : AcceptanceStep {
    override val label: String = "attack_all"
}

sealed interface AcceptanceCondition {
    val label: String
}

data class ActionAvailableCondition(
    val type: AcceptanceActionType,
    val card: String,
    val altCost: AcceptanceAltCost? = null,
) : AcceptanceCondition {
    override val label: String = "action ${type.yamlName} $card${altCost?.let { " via ${it.yamlName}" } ?: ""}"
}

data class ZoneContainsCondition(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} ${zone.yamlName} contains $card"
}

data class ZoneNotContainsCondition(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} ${zone.yamlName} lacks $card"
}

data class ZoneCountAtLeastCondition(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val count: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} ${zone.yamlName} count at least $count"
}

data class LifeTotalCondition(
    val side: AcceptanceSide,
    val value: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} life is $value"
}

data class WinnerCondition(
    val side: AcceptanceSide,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} wins"
}

data class LoserCondition(
    val side: AcceptanceSide,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} loses"
}

data class BattlefieldStatsAtLeastCondition(
    val side: AcceptanceSide,
    val card: String,
    val power: Int,
    val toughness: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} battlefield $card stats at least $power/$toughness"
}

data class BattlefieldStatsCondition(
    val side: AcceptanceSide,
    val card: String,
    val power: Int,
    val toughness: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} battlefield $card stats $power/$toughness"
}

data class PhaseCondition(
    val phase: String,
) : AcceptanceCondition {
    override val label: String = "phase is $phase"
}

data class PromptCondition(
    val prompt: String,
    val promptId: Int? = null,
) : AcceptanceCondition {
    override val label: String = "prompt $prompt${promptId?.let { "#$it" } ?: ""} seen"
}

data class AnnotationSeenCondition(
    val type: String,
) : AcceptanceCondition {
    override val label: String = "annotation $type seen"
}

data object StackEmptyCondition : AcceptanceCondition {
    override val label: String = "stack empty"
}

enum class AcceptanceActionType(
    val yamlName: String,
) {
    PlayLand("play_land"),
    Cast("cast"),
    Activate("activate"),
    ;

    companion object {
        fun parse(value: String): AcceptanceActionType =
            entries.firstOrNull { it.yamlName == value } ?: error("unknown action type: $value")
    }
}

enum class AcceptanceSide(
    val yamlName: String,
) {
    Ours("ours"),
    Opponent("opponent"),
    ;

    companion object {
        fun parse(value: String): AcceptanceSide = entries.firstOrNull { it.yamlName == value } ?: error("unknown side: $value")
    }
}

enum class AcceptanceZone(
    val yamlName: String,
) {
    Battlefield("battlefield"),
    Hand("hand"),
    Graveyard("graveyard"),
    Exile("exile"),
    Library("library"),
    Sideboard("sideboard"),
    Stack("stack"),
    ;

    companion object {
        fun parse(value: String): AcceptanceZone = entries.firstOrNull { it.yamlName == value } ?: error("unknown zone: $value")
    }
}

enum class AcceptanceCastingTimeOption(
    val yamlName: String,
) {
    Done("done"),
    Kicker("kicker"),
    AdditionalCost("additional_cost"),
    Bargain("bargain"),
    Cleave("cleave"),
    Overload("overload"),
    ;

    companion object {
        fun parse(value: String): AcceptanceCastingTimeOption =
            entries.firstOrNull { it.yamlName == value } ?: error("unknown casting time option: $value")
    }
}

enum class AcceptanceManaTypeChoice(
    val yamlName: String,
) {
    TwoGeneric("two_generic"),
    White("white"),
    Blue("blue"),
    Black("black"),
    Red("red"),
    Green("green"),
    ;

    companion object {
        fun parse(value: String): AcceptanceManaTypeChoice =
            entries.firstOrNull { it.yamlName == value } ?: error("unknown mana type choice: $value")
    }
}

enum class AcceptanceAltCost(
    val yamlName: String,
) {
    Cleave("cleave"),
    Disguise("disguise"),
    Overload("overload"),
    Escape("escape"),
    Foretell("foretell"),
    Impending("impending"),
    JumpStart("jump_start"),
    Plot("plot"),
    Warp("warp"),
    Enlist("enlist"),
    Airbend("airbend"),
    ;

    companion object {
        fun parse(value: String): AcceptanceAltCost = entries.firstOrNull { it.yamlName == value } ?: error("unknown alt cost: $value")
    }
}

sealed interface AcceptanceTargetSpec {
    val label: String
}

data class PlayerTargetSpec(
    val side: AcceptanceSide,
) : AcceptanceTargetSpec {
    override val label: String = side.yamlName
}

data class CardTargetSpec(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceTargetSpec {
    override val label: String = "${side.yamlName} ${zone.yamlName} $card"
}
