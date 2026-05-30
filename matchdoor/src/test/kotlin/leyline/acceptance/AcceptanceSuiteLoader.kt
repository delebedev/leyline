package leyline.acceptance

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AcceptanceSuiteLoader {
    fun load(name: String): AcceptanceSuite = loadFromFile(resolveSuitePath(name))

    fun loadFromFile(path: Path): AcceptanceSuite = loadFromText(Files.readString(path))

    fun loadFromText(text: String): AcceptanceSuite {
        val root = Yaml().load<Any?>(text).asMap("suite")
        return AcceptanceSuite(
            name = root.requiredString("name", "suite"),
            description = root.optionalString("description"),
            scenarios = root.requiredList("scenarios", "suite").mapIndexed(::parseScenario),
        )
    }

    private fun resolveSuitePath(name: String): Path {
        val fileName = if (name.endsWith(".yaml")) name else "$name.yaml"
        val candidates =
            listOf(
                Paths.get("puzzles/sets/$fileName"),
                Paths.get("../puzzles/sets/$fileName"),
                Paths.get("../../puzzles/sets/$fileName"),
            )
        return candidates.firstOrNull { Files.exists(it) }
            ?: error("suite not found: $fileName in $candidates (cwd=${Paths.get("").toAbsolutePath()})")
    }

    private fun parseScenario(
        index: Int,
        raw: Any?,
    ): AcceptanceScenario {
        val context = "scenario[$index]"
        val map = raw.asMap(context)
        return AcceptanceScenario(
            id = map.requiredString("id", context),
            puzzle = map.requiredString("puzzle", context),
            run = map.optionalString("run"),
            expect = map.optionalString("expect"),
            steps = map.optionalList("steps")?.mapIndexed { stepIndex, step -> parseStep(stepIndex, step) } ?: emptyList(),
        )
    }

    private fun parseStep(
        index: Int,
        raw: Any?,
    ): AcceptanceStep {
        val context = "step[$index]"
        val map = raw.asMap(context)
        require(map.size == 1) { "$context must contain exactly one step key, got ${map.keys}" }
        val (key, value) = map.entries.single()
        return when (key) {
            "wait" -> WaitStep(parseConditions(value, "$context.wait"))
            "expect" -> ExpectStep(parseConditions(value, "$context.expect"))
            "pass_until" -> parsePassUntil(value, "$context.pass_until")
            "activate" -> parseActivate(value, "$context.activate")
            "choose" -> parseChoose(value, "$context.choose")
            "modal_choice" -> parseModalChoice(value, "$context.modal_choice")
            "static_choice" -> parseStaticChoice(value, "$context.static_choice")
            "optional_action" -> parseOptionalAction(value, "$context.optional_action")
            "target" -> TargetStep(parseTarget(value, "$context.target"))
            "select_cost" -> parseSelectCost(value, "$context.select_cost")
            "select_card" -> parseSelectCard(value, "$context.select_card")
            "block" -> parseBlock(value, "$context.block")
            "play_land" -> PlayLandStep(value.asString("$context.play_land"))
            "cast" -> parseCast(value, "$context.cast")
            "resolve_stack" -> ResolveStackStep
            "attack_all" -> AttackAllStep
            else -> error("unknown step key: $key at $context")
        }
    }

    private fun parsePassUntil(
        raw: Any?,
        context: String,
    ): PassUntilStep {
        val map = raw.asMap(context)
        val maxPasses = map.optionalInt("max_passes") ?: 20
        val conditionMap = map.filterKeys { it != "max_passes" }
        return PassUntilStep(parseConditions(conditionMap, context), maxPasses)
    }

    private fun parseCast(
        raw: Any?,
        context: String,
    ): CastStep =
        when (raw) {
            is String -> CastStep(raw)
            else -> {
                val map = raw.asMap(context)
                CastStep(
                    card = map.requiredString("card", context),
                    zone = map.optionalString("zone")?.let(AcceptanceZone::parse) ?: AcceptanceZone.Hand,
                    altCost = map.optionalString("alt_cost")?.let(AcceptanceAltCost::parse),
                )
            }
        }

    private fun parseSelectCost(
        raw: Any?,
        context: String,
    ): SelectCostStep {
        val map = raw.asMap(context)
        return SelectCostStep(
            side = map.optionalString("side")?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            cards = map.requiredList("cards", context).mapIndexed { index, item -> item.asString("$context.cards[$index]") },
        )
    }

    private fun parseSelectCard(
        raw: Any?,
        context: String,
    ): SelectCardStep {
        val map = raw.asMap(context)
        return SelectCardStep(
            side = map.optionalString("side")?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseActivate(
        raw: Any?,
        context: String,
    ): ActivateStep =
        when (raw) {
            is String -> ActivateStep(raw)
            else -> {
                val map = raw.asMap(context)
                ActivateStep(
                    card = map.requiredString("card", context),
                    zone = map.optionalString("zone")?.let(AcceptanceZone::parse) ?: AcceptanceZone.Battlefield,
                    abilityIndex = map.optionalInt("ability_index") ?: 0,
                )
            }
        }

    private fun parseChoose(
        raw: Any?,
        context: String,
    ): ChooseStep {
        val map = raw.asMap(context)
        return ChooseStep(
            optionalCost = map.optionalString("optional_cost")?.let(AcceptanceCastingTimeOption::parse),
            ctoId = map.optionalInt("cto_id"),
        )
    }

    private fun parseModalChoice(
        raw: Any?,
        context: String,
    ): ModalChoiceStep {
        val map = raw.asMap(context)
        return ModalChoiceStep(index = map.requiredInt("index", context))
    }

    private fun parseStaticChoice(
        raw: Any?,
        context: String,
    ): StaticChoiceStep {
        val map = raw.asMap(context)
        return StaticChoiceStep(id = map.requiredInt("id", context))
    }

    private fun parseOptionalAction(
        raw: Any?,
        context: String,
    ): OptionalActionStep {
        val map = raw.asMap(context)
        return OptionalActionStep(accept = map.requiredBoolean("accept", context))
    }

    private fun parseBlock(
        raw: Any?,
        context: String,
    ): BlockStep {
        val map = raw.asMap(context)
        return BlockStep(
            blocker = map.requiredString("blocker", context),
            attacker = map.requiredString("attacker", context),
        )
    }

    private fun parseTarget(
        raw: Any?,
        context: String,
    ): AcceptanceTargetSpec =
        when (raw) {
            is String -> PlayerTargetSpec(AcceptanceSide.parse(raw))
            else -> {
                val map = raw.asMap(context)
                CardTargetSpec(
                    side = AcceptanceSide.parse(map.requiredString("side", context)),
                    zone = AcceptanceZone.parse(map.requiredString("zone", context)),
                    card = map.requiredString("card", context),
                )
            }
        }

    private fun parseConditions(
        raw: Any?,
        context: String,
    ): List<AcceptanceCondition> {
        val map = raw.asMap(context)
        require(map.isNotEmpty()) { "$context must contain at least one condition" }
        return map.flatMap { (key, value) ->
            if (key == "all") {
                value.asList("$context.all").mapIndexed { index, item ->
                    val itemMap = item.asMap("$context.all[$index]")
                    require(itemMap.size == 1) { "$context.all[$index] must contain exactly one condition key" }
                    val (itemKey, itemValue) = itemMap.entries.single()
                    parseCondition(itemKey, itemValue, "$context.all[$index]")
                }
            } else {
                listOf(parseCondition(key, value, context))
            }
        }
    }

    private fun parseCondition(
        key: String,
        value: Any?,
        context: String,
    ): AcceptanceCondition =
        when (key) {
            "action" -> parseActionAvailable(value, "$context.action")
            "zone_contains" -> parseZoneContains(value, "$context.zone_contains")
            "zone_not_contains" -> parseZoneNotContains(value, "$context.zone_not_contains")
            "zone_count_at_least" -> parseZoneCountAtLeast(value, "$context.zone_count_at_least")
            "battlefield_stats" -> parseBattlefieldStats(value, "$context.battlefield_stats")
            "battlefield_stats_at_least" -> parseBattlefieldStatsAtLeast(value, "$context.battlefield_stats_at_least")
            "opponent_life" -> LifeTotalCondition(AcceptanceSide.Opponent, value.asInt("$context.opponent_life"))
            "our_life" -> LifeTotalCondition(AcceptanceSide.Ours, value.asInt("$context.our_life"))
            "phase" -> PhaseCondition(value.asString("$context.phase"))
            "prompt" -> PromptCondition(value.asString("$context.prompt"))
            "stack_empty" -> {
                require(value.asBoolean("$context.stack_empty")) { "$context.stack_empty only supports true" }
                StackEmptyCondition
            }
            else -> error("unknown condition key: $key at $context")
        }

    private fun parseActionAvailable(
        raw: Any?,
        context: String,
    ): ActionAvailableCondition {
        val map = raw.asMap(context)
        return ActionAvailableCondition(
            type = AcceptanceActionType.parse(map.requiredString("type", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseZoneContains(
        raw: Any?,
        context: String,
    ): ZoneContainsCondition {
        val map = raw.asMap(context)
        return ZoneContainsCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseZoneNotContains(
        raw: Any?,
        context: String,
    ): ZoneNotContainsCondition {
        val map = raw.asMap(context)
        return ZoneNotContainsCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseZoneCountAtLeast(
        raw: Any?,
        context: String,
    ): ZoneCountAtLeastCondition {
        val map = raw.asMap(context)
        return ZoneCountAtLeastCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            count = map.requiredInt("count", context),
        )
    }

    private fun parseBattlefieldStatsAtLeast(
        raw: Any?,
        context: String,
    ): BattlefieldStatsAtLeastCondition {
        val map = raw.asMap(context)
        return BattlefieldStatsAtLeastCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            card = map.requiredString("card", context),
            power = map.requiredInt("power", context),
            toughness = map.requiredInt("toughness", context),
        )
    }

    private fun parseBattlefieldStats(
        raw: Any?,
        context: String,
    ): BattlefieldStatsCondition {
        val map = raw.asMap(context)
        return BattlefieldStatsCondition(
            side = map.optionalString("side")?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            card = map.requiredString("card", context),
            power = map.requiredInt("power", context),
            toughness = map.requiredInt("toughness", context),
        )
    }
}

private fun Any?.asMap(context: String): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: error("$context must be a map")
    return raw.mapKeys { (key, _) -> key as? String ?: error("$context has non-string key: $key") }
}

private fun Any?.asString(context: String): String = this as? String ?: error("$context must be a string")

private fun Any?.asInt(context: String): Int =
    when (this) {
        is Int -> this
        is Number -> toInt()
        else -> error("$context must be a number")
    }

private fun Any?.asBoolean(context: String): Boolean = this as? Boolean ?: error("$context must be a boolean")

private fun Any?.asList(context: String): List<Any?> = this as? List<Any?> ?: error("$context must be a list")

private fun Map<String, Any?>.requiredString(
    key: String,
    context: String,
): String = this[key].asString("$context.$key")

private fun Map<String, Any?>.optionalString(key: String): String? = this[key]?.asString(key)

private fun Map<String, Any?>.optionalInt(key: String): Int? = this[key]?.asInt(key)

private fun Map<String, Any?>.requiredInt(
    key: String,
    context: String,
): Int = this[key].asInt("$context.$key")

private fun Map<String, Any?>.requiredBoolean(
    key: String,
    context: String,
): Boolean = this[key].asBoolean("$context.$key")

private fun Map<String, Any?>.requiredList(
    key: String,
    context: String,
): List<Any?> = optionalList(key) ?: error("$context.$key must be a list")

private fun Map<String, Any?>.optionalList(key: String): List<Any?>? {
    val value = this[key] ?: return null
    return value as? List<Any?> ?: error("$key must be a list")
}
