package leyline.bridge.coord

import forge.card.ColorSet
import forge.card.ICardFace
import forge.card.MagicColor
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.player.PlayerController.BinaryChoiceType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.AutomaticAnswerRule
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.interaction.ConfirmActionPlan
import leyline.bridge.types.StaticChoiceIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Routes Forge static enum choices through static-list SelectN prompts. */
class StaticChoiceCoordinator(
    private val bridge: InteractivePromptBridge,
) {
    fun chooseNumber(
        sa: SpellAbility,
        title: String,
        values: List<Int>,
    ): Int {
        require(values.isNotEmpty()) { "Number choice requires an offered value" }
        if (values.size == 1) return values.single()
        val selected =
            bridge.requestStaticChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = title,
                    options = values.map(Int::toString),
                    route = PromptRouteResolver.resolve(PromptSemantic.NamedChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticOptionIds = values.indices.map { it + 1 },
                ),
            )
        return values[selected.single()]
    }

    /**
     * Asks which of two separated piles to take. Forge's GUI form re-asks until a
     * pile caption is picked, so the choice is offered over the two piles directly.
     * [faceDown] is Forge's FaceDown value: "False" shows both piles, "One" hides
     * pile 1, "True" hides both. Returns true when pile 1 is chosen.
     */
    fun chooseCardsPile(
        sa: SpellAbility,
        pile1: CardCollectionView,
        pile2: CardCollectionView,
        faceDown: String,
    ): Boolean {
        val selected =
            bridge.requestStaticChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = "Choose a pile",
                    options =
                        listOf(
                            pileLabel(1, pile1, shown = faceDown == "False"),
                            pileLabel(2, pile2, shown = faceDown != "True"),
                        ),
                    route = PromptRouteResolver.resolve(PromptSemantic.NamedChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticOptionIds = listOf(1, 2),
                ),
            )
        return selected.firstOrNull() != 1
    }

    private fun pileLabel(
        number: Int,
        pile: CardCollectionView,
        shown: Boolean,
    ): String =
        when {
            pile.isEmpty() -> "Pile $number: no cards"
            shown -> "Pile $number: ${pile.joinToString { it.name }}"
            else -> "Pile $number: ${pile.size} face-down card${if (pile.size == 1) "" else "s"}"
        }

    fun chooseDungeon(
        faces: List<ICardFace>,
        sa: SpellAbility,
    ): ICardFace {
        val ids = faces.map { bridge.resolveCardNameGrpId(it.name) }
        return faces[catalogChoice(faces.map { it.name }, ids, sa, PromptSemantic.DungeonChoice)]
    }

    fun chooseRoom(
        spells: List<SpellAbility>,
        sa: SpellAbility,
    ): SpellAbility {
        val ids = spells.map { checkNotNull(bridge.resolveAbilityIdentity(it)) { "Dungeon room has no identity" }.abilityGrpId }
        return spells[catalogChoice(spells.map { it.getParam("RoomName") }, ids, sa, PromptSemantic.DungeonRoomChoice)]
    }

    private fun catalogChoice(
        labels: List<String>,
        ids: List<Int>,
        sa: SpellAbility,
        semantic: PromptSemantic,
    ): Int {
        val stack =
            sa.hostCard.game.stack
                .firstOrNull()
        return checkNotNull(
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_one",
                        message = "Choose dungeon or room",
                        options = labels,
                        route = PromptRouteResolver.resolve(semantic),
                        staticOptionIds = ids,
                        sourceEntityId = stack?.sourceCard?.id ?: sa.hostCard.id,
                        forgeAbilityId = stack?.takeUnless { it.isSpell }?.spellAbility?.id ?: 0,
                    ),
                ).singleOrNull(),
        ) { "Dungeon choice requires one answer" }
    }

    fun confirmAction(
        plan: ConfirmActionPlan.Choice,
        message: String,
    ): Boolean {
        val parityIds = (plan as? ConfirmActionPlan.Choice.Parity)?.optionIds
        val result =
            requestChoice(
                PromptRequest(
                    promptType = "confirm",
                    message = message,
                    options = plan.options,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    route = plan.route,
                    sourceEntityId = plan.sourceEntityId,
                    staticList = if (parityIds != null) StaticList.Parities else null,
                    staticOptionIds = parityIds ?: plan.options.indices.map { it + 1 },
                ),
            )
        return result.firstOrNull() == 0
    }

    fun chooseBinary(
        sa: SpellAbility?,
        question: String?,
        kindOfChoice: BinaryChoiceType?,
        defaultVal: Boolean?,
    ): Boolean {
        val labels = binaryLabels(kindOfChoice)
        val parityIds = StaticChoiceIds.parityIdsForNames(labels)
        val result =
            requestChoice(
                PromptRequest(
                    promptType = "confirm",
                    message = question ?: "Choose one",
                    options = labels,
                    min = 1,
                    max = 1,
                    defaultIndex = if (defaultVal != false) 0 else 1,
                    route =
                        when {
                            parityIds != null -> PromptRouteResolver.resolve(PromptSemantic.StaticParityChoice)
                            kindOfChoice == BinaryChoiceType.HeadsOrTails ->
                                ResolvedPromptRoute.AutoResolve(PromptSemantic.Generic, AutomaticAnswerRule.CoinFlipCall)
                            else -> PromptRouteResolver.resolve(PromptSemantic.Generic)
                        },
                    sourceEntityId = sourceEntityId(sa),
                    staticList = if (parityIds != null) StaticList.Parities else null,
                    staticOptionIds = parityIds.orEmpty(),
                ),
            )
        return result.firstOrNull() == 0
    }

    fun chooseColor(
        message: String,
        sa: SpellAbility?,
        colors: ColorSet,
        askForManaAbility: Boolean = false,
    ): Byte {
        val cntColors = colors.countColors()
        if (cntColors == 0) return 0
        if (cntColors == 1) return colors.color
        if (sa?.isManaAbility() == true && !askForManaAbility) return colors.orderedColors.first().colorMask

        val colorChoices = colors.orderedColors.toList()
        val colorOptions = colorChoices.map { it.translatedName }
        log.debug("chooseColor: options={}", colorOptions)
        val indices =
            requestChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = message,
                    options = colorOptions,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = StaticList.Colors,
                    staticOptionIds = colorChoices.mapNotNull { StaticChoiceIds.colorIdForMask(it.colorMask) },
                ),
            )
        val idx = indices.firstOrNull() ?: return 0
        if (idx >= colorOptions.size) return 0
        return colorChoices[idx].colorMask
    }

    /** Returns the selected protection quality, preserving Forge's offered strings and order. */
    fun chooseProtectionType(
        sa: SpellAbility,
        choices: List<String>,
    ): String? {
        if (choices.isEmpty()) return null
        val ids =
            choices.map { choice ->
                if (choice.equals("colorless", ignoreCase = true)) {
                    0
                } else {
                    StaticChoiceIds.colorIdForName(choice)
                }
            }
        val colorOnly = ids.all { it != null }
        val selected =
            requestChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = "Choose protection",
                    options = choices,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(if (colorOnly) PromptSemantic.StaticColorChoice else PromptSemantic.NamedChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = StaticList.CardColors.takeIf { colorOnly },
                    staticOptionIds = if (colorOnly) ids.filterNotNull() else choices.indices.map { it + 1 },
                ),
            ).firstOrNull()
        return choices.getOrNull(selected ?: 0)
    }

    fun chooseColorAllowColorless(
        message: String,
        card: Card,
        colors: ColorSet,
    ): Byte {
        if (colors.isColorless) return MagicColor.COLORLESS
        val colorChoices = colors.orderedColors.toList() + MagicColor.Color.COLORLESS
        val indices =
            requestChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = message,
                    options = colorChoices.map { it.translatedName },
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                    sourceEntityId = card.id,
                    staticList = StaticList.CardColors,
                    staticOptionIds =
                        colorChoices.map {
                            if (it == MagicColor.Color.COLORLESS) 0 else checkNotNull(StaticChoiceIds.colorIdForMask(it.colorMask))
                        },
                ),
            )
        return colorChoices.getOrNull(indices.firstOrNull() ?: -1)?.colorMask ?: MagicColor.COLORLESS
    }

    fun chooseColors(
        message: String,
        sa: SpellAbility?,
        min: Int,
        max: Int,
        options: ColorSet,
    ): ColorSet {
        if (options.countColors() == 0) return ColorSet.fromMask(0)
        if (options.countColors() == min && min == max) return options

        val colorChoices = options.orderedColors.toList()
        val indices =
            requestChoice(
                PromptRequest(
                    promptType = "choose_colors",
                    message = message,
                    options = colorChoices.map { it.translatedName },
                    min = min,
                    max = max,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = if (options.isAllColors) StaticList.Colors else StaticList.CardColors,
                    staticOptionIds = colorChoices.mapNotNull { StaticChoiceIds.colorIdForMask(it.colorMask) },
                ),
            )
        val mask = indices.fold(0) { acc, idx -> acc or (colorChoices.getOrNull(idx)?.colorMask?.toInt() ?: 0) }
        return ColorSet.fromMask(mask)
    }

    /** Each mana unit gets a correlated static-color choice; counts retain repeated colors. */
    fun specifyManaCombo(
        sa: SpellAbility,
        colors: ColorSet,
        amount: Int,
        different: Boolean,
    ): Map<Byte, Int> {
        val allocation = linkedMapOf<Byte, Int>()
        var available = colors
        repeat(amount) { index ->
            val selected = chooseColors("Choose mana (${index + 1} of $amount)", sa, 1, 1, available).color
            allocation[selected] = (allocation[selected] ?: 0) + 1
            if (different) available = ColorSet.fromMask(available.color.toInt() and selected.toInt().inv())
        }
        return allocation
    }

    fun chooseSomeType(
        kindOfType: String,
        sa: SpellAbility?,
        validTypes: Collection<String>,
        isOptional: Boolean,
    ): String? {
        val isCardType = kindOfType == "Card"

        fun request(choices: List<Pair<String, Int>>) =
            PromptRequest(
                promptType = "choose_type",
                message = "Choose a ${kindOfType.lowercase()} type",
                options = choices.map { it.first },
                min = if (isOptional) 0 else 1,
                max = 1,
                defaultIndex = 0,
                route =
                    PromptRouteResolver.resolve(
                        if (isCardType) PromptSemantic.StaticCardTypeChoice else PromptSemantic.StaticSubtypeChoice,
                    ),
                sourceEntityId = sourceEntityId(sa),
                staticList = if (isCardType) StaticList.CardTypes else StaticList.SubTypes,
                staticOptionIds = choices.map { it.second },
            )

        // Only values with a protocol mapping can be shown; the rest cannot be chosen.
        val choices =
            if (isCardType) {
                validTypes.mapNotNull { type -> StaticChoiceIds.cardTypeIdFor(type)?.let { type to it } }
            } else {
                validTypes.sorted().mapNotNull { type -> StaticChoiceIds.subtypeIdFor(type)?.let { type to it } }
            }
        if (choices.size < 2 && !(isOptional && choices.size == 1)) {
            val answer = if (isOptional) null else choices.singleOrNull()?.first ?: validTypes.firstOrNull()
            val recorded = if (choices.isEmpty()) validTypes.map { it to 0 } else choices
            bridge.recordAutomaticAnswer(
                request(recorded),
                AutomaticAnswerRule.NoChoiceAmongShownOptions,
                listOfNotNull(answer?.let { recorded.indexOfFirst { pair -> pair.first == it } }),
            )
            return answer
        }

        val idx = requestChoice(request(choices)).firstOrNull()
        return idx?.let { choices.getOrNull(it)?.first } ?: if (isOptional) null else choices.first().first
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun binaryLabels(kindOfChoice: BinaryChoiceType?): List<String> =
        when (kindOfChoice) {
            BinaryChoiceType.HeadsOrTails -> listOf("Heads", "Tails")
            BinaryChoiceType.TapOrUntap -> listOf("Tap", "Untap")
            BinaryChoiceType.OddsOrEvens -> listOf("Odds", "Evens")
            BinaryChoiceType.UntapOrLeaveTapped -> listOf("Untap", "Leave Tapped")
            BinaryChoiceType.PlayOrDraw -> listOf("Play", "Draw")
            BinaryChoiceType.LeftOrRight -> listOf("Left", "Right")
            BinaryChoiceType.AddOrRemove -> listOf("Add Counter", "Remove Counter")
            BinaryChoiceType.IncreaseOrDecrease -> listOf("Increase", "Decrease")
            else -> listOf("Yes", "No")
        }

    private fun sourceEntityId(sa: SpellAbility?): Int? = sa?.hostCard?.id?.takeIf { it > 0 }

    private fun requestChoice(request: PromptRequest): List<Int> =
        if (request.route is ResolvedPromptRoute.StaticChoice) {
            bridge.requestStaticChoice(request)
        } else {
            bridge.requestChoice(request)
        }

    companion object {
        private val log = LoggerFactory.getLogger(StaticChoiceCoordinator::class.java)
    }
}
