package leyline.game.mapper

import forge.ai.ComputerUtilMana
import forge.card.CardStateName
import forge.game.Game
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.cost.CostAdjustment
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.bridge.chooseCastAbility
import leyline.bridge.getAllCastableAbilities
import leyline.game.AbilityRegistry
import leyline.game.CardData
import leyline.game.GameBridge
import leyline.game.ManaColorMapping
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Maps Forge playable actions to Arena [Action] / [ActionsAvailableReq] protos.
 *
 * Depends on [IdMapping] (instanceId allocation) and [PlayerLookup] (seat → player).
 * Extracted from [StateMapper] for independent testability.
 */
object ActionMapper {

    private val log = LoggerFactory.getLogger(ActionMapper::class.java)

    private const val INITIAL_MANA_ID = 10

    fun buildActions(game: Game, seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        buildActionList(seatId, bridge, checkLegality = true)

    /**
     * Naive action list: Cast for all non-lands, Play for all lands in hand,
     * ActivateMana for untapped permanents — no canPlay/canPay checks.
     * Real server embeds human's potential actions during AI turn regardless of phase.
     */
    fun buildNaiveActions(seatId: Int, bridge: GameBridge): ActionsAvailableReq =
        buildActionList(seatId, bridge, checkLegality = false)

    /**
     * Shared action list builder — bridge-backed overload.
     * Delegates to the pure overload with function params extracted from [bridge].
     */
    internal fun buildActionList(
        seatId: Int,
        bridge: GameBridge,
        checkLegality: Boolean,
    ): ActionsAvailableReq {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return passOnlyActions()
        return buildActionList(
            player = player,
            seatId = seatId,
            checkLegality = checkLegality,
            idResolver = { forgeCardId -> bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
            grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, bridge.cards) },
            cardDataLookup = { grpId -> bridge.cards.findByGrpId(grpId) },
            abilityRegistryLookup = { card, cardData -> bridge.abilityRegistryFor(card, cardData) },
        )
    }

    /**
     * Shared action list builder — pure overload with function params.
     *
     * @param player Forge player for the seat.
     * @param seatId Arena seat identifier (for logging).
     * @param checkLegality true → full legality checks (canPlayLand, canPayManaCost,
     *   activated ability canPlay, autoTapSolution, inactive land actions).
     *   false → naive mode (everything playable, no autoTap, no Activate abilities).
     * @param idResolver forgeCardId → instanceId.
     * @param grpIdResolver card → grpId (handles both battlefield and hand cards).
     * @param cardDataLookup grpId → CardData (nullable).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // inherent complexity — action types × legality modes
    internal fun buildActionList(
        player: Player,
        seatId: Int,
        checkLegality: Boolean,
        idResolver: (Int) -> Int,
        grpIdResolver: (Card) -> Int,
        cardDataLookup: (Int) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry? = { _, _ -> null },
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()

        // Battlefield permanents: ActivateMana + Activate
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            // Naive mode only cares about ActivateMana — skip tapped cards entirely
            if (!checkLegality && card.isTapped) continue

            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)

            // ActivateMana — untapped permanents with mana abilities
            if (!card.isTapped && card.manaAbilities.isNotEmpty()) {
                builder.addActions(buildActivateManaAction(card, instanceId, grpId, cardDataLookup, abilityRegistryLookup))
            }

            // Activate — non-mana activated abilities (only with legality checks)
            if (checkLegality) {
                val cardData = cardDataLookup(grpId)
                for (ability in card.spellAbilities) {
                    ability.setActivatingPlayer(player)
                    if (!ability.isActivatedAbility) continue
                    if (ability.isManaAbility()) continue
                    if (!ability.canPlay()) continue
                    val actionBuilder = Action.newBuilder()
                        .setActionType(ActionType.Activate_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
                    val registry = abilityRegistryLookup(card, cardData)
                    val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                    if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                    builder.addActions(actionBuilder)
                }
            }
        }

        // Hand cards: Lands + Spells
        val handCards = player.getZone(ForgeZoneType.Hand).cards

        // Lands: playable → actions, not playable → inactiveActions (legality only)
        for (card in CardLists.filter(handCards, CardPredicates.LANDS)) {
            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)
            val canPlay = if (checkLegality) {
                val landAbility = LandAbility(card, card.currentState)
                landAbility.activatingPlayer = player
                player.canPlayLand(card, false, landAbility)
            } else {
                false
            }
            if (canPlay) {
                builder.addActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)),
                )
            } else {
                // Greyed-out: land can't be played (already played one this turn)
                builder.addInactiveActions(
                    Action.newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setGrpId(grpId)
                        .setInstanceId(instanceId)
                        .setFacetId(instanceId),
                )
            }
        }

        // Non-land spells (Cast before Activate_add3 — client uses emission order for text assignment)
        for (card in CardLists.filter(handCards, CardPredicates.NON_LANDS)) {
            var sa: SpellAbility? = null
            if (checkLegality) {
                sa = chooseCastAbility(card, player) ?: continue
                val canPay = try {
                    ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
                if (!canPay) continue
            }
            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)
            val actionBuilder = Action.newBuilder()
                .setActionType(ActionType.Cast)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

            // Cost: use Forge's effective cost (includes reductions/tax) when available,
            // fall back to static card DB cost for naive mode
            val effectiveCost = sa?.let { computeEffectiveCost(it, player) }
            if (effectiveCost != null && !effectiveCost.isNoCost) {
                addManaCostFromForge(effectiveCost, actionBuilder)
                if (checkLegality) {
                    val costPairs = forgeManaCostToPairs(effectiveCost)
                    val autoTap = buildAutoTapSolution(costPairs, player, idResolver, grpIdResolver, cardDataLookup, abilityRegistryLookup)
                    if (autoTap != null) actionBuilder.setAutoTapSolution(autoTap)
                }
            } else {
                val cardData = cardDataLookup(grpId)
                if (cardData != null) {
                    for ((color, count) in cardData.manaCost) {
                        actionBuilder.addManaCost(
                            ManaRequirement.newBuilder().addColor(color).setCount(count),
                        )
                    }
                }
            }
            builder.addActions(actionBuilder)

            // CastAdventure for adventure-capable cards
            if (card.isAdventureCard) {
                buildAdventureAction(card, player, instanceId, grpId, checkLegality)
                    ?.let { builder.addActions(it) }
            }
        }

        // Hand cards: activated abilities with non-battlefield activation zones (Channel, etc.)
        // Real server sends: instanceId + abilityGrpId + manaCost — no grpId/facetId.
        // Including grpId causes the client to render card text instead of ability text.
        if (checkLegality) {
            for (card in handCards) {
                for (ability in card.spellAbilities) {
                    ability.setActivatingPlayer(player)
                    if (!ability.isActivatedAbility) continue
                    if (ability.isManaAbility()) continue
                    if (!ability.canPlay()) continue // Forge checks ActivationZone restriction
                    val instanceId = idResolver(card.id)
                    val grpId = grpIdResolver(card)
                    val cardData = cardDataLookup(grpId)
                    val actionBuilder = Action.newBuilder()
                        .setActionType(ActionType.Activate_add3)
                        .setInstanceId(instanceId)
                    val registry = abilityRegistryLookup(card, cardData)
                    val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                    if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                    // Wire requires manaCost with abilityGrpId echoed in each ManaRequirement
                    val abilityCost = ability.payCosts?.totalMana
                    if (abilityCost != null && !abilityCost.isNoCost) {
                        addManaCostFromForge(abilityCost, actionBuilder, abilityGrpId)
                    }
                    builder.addActions(actionBuilder)
                }
            }
        }

        // Zone casts: Graveyard, Exile, Command (flashback, escape, etc.)
        if (checkLegality) {
            addZoneCastActions(player, builder, idResolver, grpIdResolver, cardDataLookup)
        }
        // Pass + FloatMana always available
        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        // Logging
        val manaCount = builder.actionsList.count { it.actionType == ActionType.ActivateMana }
        val landCount = builder.actionsList.count { it.actionType == ActionType.Play_add3 }
        val castCount = builder.actionsList.count { it.actionType == ActionType.Cast }
        if (checkLegality) {
            val activateCount = builder.actionsList.count { it.actionType == ActionType.Activate_add3 }
            log.info("buildActions: seat={} mana={} activate={} lands={} casts={} total={}", seatId, manaCount, activateCount, landCount, castCount, builder.actionsCount)
        } else {
            log.info("buildNaiveActions: seat={} mana={} lands={} casts={} total={}", seatId, manaCount, landCount, castCount, builder.actionsCount)
        }

        return builder.build()
    }

    /** Build an ActivateMana action for an untapped permanent with mana abilities. */
    private fun buildActivateManaAction(
        card: Card,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (Int) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): Action {
        val cardData = cardDataLookup(grpId)
        val sa = card.manaAbilities.first()
        val registry = abilityRegistryLookup(card, cardData)
        val abilityGrpId = registry?.forSpellAbility(sa.id) ?: 0
        val produced = sa.manaPart?.origProduced ?: ""
        val manaColor = producedToManaColor(produced) ?: ManaColor.Generic

        val actionBuilder = Action.newBuilder()
            .setActionType(ActionType.ActivateMana)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)
            .setIsBatchable(true)
        if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)

        actionBuilder.addManaPaymentOptions(
            ManaPaymentOption.newBuilder().addMana(
                ManaInfo.newBuilder()
                    .setManaId(10)
                    .setColor(manaColor)
                    .setSrcInstanceId(instanceId)
                    .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                    .setAbilityGrpId(abilityGrpId)
                    .setCount(1),
            ),
        )

        actionBuilder.addManaSelections(
            ManaSelection.newBuilder()
                .setInstanceId(instanceId)
                .setAbilityGrpId(abilityGrpId)
                .addOptions(
                    ManaSelectionOption.newBuilder().addMana(
                        ManaColorCount.newBuilder().setColor(manaColor).setCount(1),
                    ),
                ),
        )

        return actionBuilder.build()
    }

    /** Build a CastAdventure action for an adventure card, or null if not castable. */
    private fun buildAdventureAction(
        card: Card,
        player: Player,
        instanceId: Int,
        creatureGrpId: Int,
        checkLegality: Boolean,
    ): Action? {
        val adventureState = card.getState(CardStateName.Secondary) ?: return null
        val adventureSa = adventureState.nonManaAbilities?.firstOrNull() ?: return null

        if (checkLegality) {
            adventureSa.setActivatingPlayer(player)
            val canCast = try {
                adventureSa.canPlay() && ComputerUtilMana.canPayManaCost(adventureSa, player, 0, false)
            } catch (_: Exception) {
                false
            }
            if (!canCast) return null
        }

        // grpId = creature face — client can't resolve IsPrimaryCard=0 adventure
        // faces and rejects the action if grpId is unknown. manaCost from the
        // adventure SA provides the correct cost for the Choose One modal.
        val builder = Action.newBuilder()
            .setActionType(ActionType.CastAdventure)
            .setInstanceId(instanceId)
            .setGrpId(creatureGrpId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastAdventure))
        val advEffective = computeEffectiveCost(adventureSa, player)
        if (advEffective != null && !advEffective.isNoCost) {
            addManaCostFromForge(advEffective, builder)
        } else {
            // Fallback: raw SA cost (adventure face always has its own cost)
            val advManaCost = adventureSa.payCosts?.totalMana
            if (advManaCost != null && !advManaCost.isNoCost) {
                addManaCostFromForge(advManaCost, builder)
            }
        }
        return builder.build()
    }

    private fun addZoneCastActions(
        player: Player,
        builder: ActionsAvailableReq.Builder,
        idResolver: (Int) -> Int,
        grpIdResolver: (Card) -> Int,
        cardDataLookup: (Int) -> CardData?,
    ) {
        val game = player.game ?: return
        val zones = listOf(ForgeZoneType.Graveyard, ForgeZoneType.Exile, ForgeZoneType.Command)
        for (card in game.getCardsIn(zones)) {
            val castable = getAllCastableAbilities(card, player)
            if (castable.isEmpty()) continue
            val sa = castable.first()

            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)
            val actionBuilder = Action.newBuilder()
                .setActionType(ActionType.Cast)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

            // Set abilityGrpId from the alternate cost keyword (flashback, escape, etc.)
            val cardData = cardDataLookup(grpId)
            val altCost = sa.alternativeCost
            if (altCost != null) {
                val altCostName = altCost.name.uppercase()
                val abilityGrpId = cardData?.keywordAbilityGrpIds?.entries
                    ?.firstOrNull { it.key.startsWith(altCostName) }?.value ?: 0
                if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
            }

            // Mana cost: use effective cost (includes commander tax + reductions)
            val effectiveCost = computeEffectiveCost(sa, player)
            if (effectiveCost != null && !effectiveCost.isNoCost) {
                addManaCostFromForge(effectiveCost, actionBuilder)
            } else if (cardData != null) {
                for ((color, count) in cardData.manaCost) {
                    actionBuilder.addManaCost(
                        ManaRequirement.newBuilder().addColor(color).setCount(count),
                    )
                }
            }

            builder.addActions(actionBuilder)
        }
    }

    internal fun passOnlyActions(): ActionsAvailableReq =
        ActionsAvailableReq.newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

    /**
     * Greedy auto-tap solver: maps mana cost requirements to untapped mana sources.
     * Returns null if no complete solution found (spell still castable via manual tap).
     */
    @Suppress("CyclomaticComplexMethod") // greedy matching has inherent branching
    private fun buildAutoTapSolution(
        manaCost: List<Pair<ManaColor, Int>>,
        player: Player,
        idResolver: (Int) -> Int,
        grpIdResolver: (Card) -> Int,
        cardDataLookup: (Int) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): AutoTapSolution? {
        if (manaCost.isEmpty()) return null

        data class ManaSource(val card: Card, val instanceId: Int, val color: ManaColor, val abilityGrpId: Int)

        // Collect untapped mana sources with their produced color
        val sources = mutableListOf<ManaSource>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            for (sa in card.manaAbilities) {
                sa.setActivatingPlayer(player)
                if (!sa.canPlay()) continue
                val produced = sa.manaPart?.origProduced ?: continue
                val manaColor = producedToManaColor(produced) ?: continue
                val instanceId = idResolver(card.id)
                val grpId = grpIdResolver(card)
                val cardData = cardDataLookup(grpId)
                val registry = abilityRegistryLookup(card, cardData)
                val abilityGrpId = registry?.forSpellAbility(sa.id) ?: 0
                sources.add(ManaSource(card, instanceId, manaColor, abilityGrpId))
            }
        }

        // Greedy match: colored requirements first, then generic
        val used = mutableSetOf<Int>() // indices into sources
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>() // (source, paying color)
        val coloredReqs = manaCost.filter { it.first != ManaColor.Generic }
        val genericReqs = manaCost.filter { it.first == ManaColor.Generic }

        // Match colored requirements
        for ((reqColor, reqCount) in coloredReqs) {
            var remaining = reqCount
            for ((idx, src) in sources.withIndex()) {
                if (remaining <= 0) break
                if (idx in used) continue
                if (src.color == reqColor) {
                    used.add(idx)
                    matched.add(src to reqColor)
                    remaining--
                }
            }
            if (remaining > 0) return null // can't fulfill colored requirement
        }

        // Match generic requirements (any color)
        for ((_, reqCount) in genericReqs) {
            var remaining = reqCount
            for ((idx, src) in sources.withIndex()) {
                if (remaining <= 0) break
                if (idx in used) continue
                used.add(idx)
                matched.add(src to src.color)
                remaining--
            }
            if (remaining > 0) return null
        }

        // Build AutoTapSolution matching real server format:
        // Each AutoTapAction has manaPaymentOption with full ManaInfo
        val builder = AutoTapSolution.newBuilder()
        var manaIdCounter = INITIAL_MANA_ID
        for ((src, payingColor) in matched) {
            val manaId = manaIdCounter++
            builder.addAutoTapActions(
                AutoTapAction.newBuilder()
                    .setInstanceId(src.instanceId)
                    .setAbilityGrpId(src.abilityGrpId)
                    .setManaPaymentOption(
                        ManaPaymentOption.newBuilder().addMana(
                            ManaInfo.newBuilder()
                                .setManaId(manaId)
                                .setColor(payingColor)
                                .setSrcInstanceId(src.instanceId)
                                .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                                .setAbilityGrpId(src.abilityGrpId)
                                .setCount(1),
                        ),
                    ),
            )
        }
        return builder.build()
    }

    /**
     * Compute the effective mana cost for a spell, including static cost reductions
     * (e.g. "spells cost {1} less") and cost raises (commander tax, etc.).
     *
     * Uses Forge's [CostAdjustment] two-stage pipeline:
     * 1. `adjust(Cost)` → commander tax + raise cost effects
     * 2. `adjust(ManaCostBeingPaid)` → static cost reductions (ReduceCost abilities)
     *
     * Returns null if the spell has no mana cost.
     */
    internal fun computeEffectiveCost(sa: SpellAbility, player: Player): forge.card.mana.ManaCost? {
        val baseCost = sa.payCosts ?: return null
        val adjusted = CostAdjustment.adjust(baseCost, sa, false)
        val manaCost = adjusted.totalMana ?: return null
        if (manaCost.isNoCost) return null
        val beingPaid = ManaCostBeingPaid(manaCost)
        CostAdjustment.adjust(beingPaid, sa, player, null, true, false)
        return beingPaid.toManaCost()
    }

    /**
     * Convert a Forge [ManaCost][forge.card.mana.ManaCost] to `List<Pair<ManaColor, Int>>`
     * for use with [buildAutoTapSolution] which expects that format.
     */
    internal fun forgeManaCostToPairs(manaCost: forge.card.mana.ManaCost): List<Pair<ManaColor, Int>> {
        val colorCounts = mutableMapOf<ManaColor, Int>()
        for (shard in manaCost) {
            val color = producedToManaColor(shard.toString().removeSurrounding("{", "}")) ?: continue
            colorCounts[color] = (colorCounts[color] ?: 0) + 1
        }
        val result = mutableListOf<Pair<ManaColor, Int>>()
        for ((color, count) in colorCounts) {
            result.add(color to count)
        }
        val generic = manaCost.genericCost
        if (generic > 0) {
            result.add(ManaColor.Generic to generic)
        }
        return result
    }

    /**
     * Convert a Forge [ManaCost] into proto [ManaRequirement] entries on an action builder.
     *
     * When [abilityGrpId] is set, each [ManaRequirement] embeds it — real server sends this
     * for hand-zone activated abilities (Channel, Ninjutsu, etc.) so the client can associate
     * cost display with the specific ability modal option.
     */
    private fun addManaCostFromForge(
        manaCost: forge.card.mana.ManaCost,
        actionBuilder: Action.Builder,
        abilityGrpId: Int? = null,
    ) {
        // Colored shards: each shard is one pip (e.g. ManaCostShard.RED → "{R}")
        val colorCounts = mutableMapOf<ManaColor, Int>()
        for (shard in manaCost) {
            val color = producedToManaColor(shard.toString().removeSurrounding("{", "}")) ?: continue
            colorCounts[color] = (colorCounts[color] ?: 0) + 1
        }
        for ((color, count) in colorCounts) {
            val req = ManaRequirement.newBuilder().addColor(color).setCount(count)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            actionBuilder.addManaCost(req)
        }
        // Generic mana
        val generic = manaCost.genericCost
        if (generic > 0) {
            val req = ManaRequirement.newBuilder().addColor(ManaColor.Generic).setCount(generic)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            actionBuilder.addManaCost(req)
        }
    }

    /** Map Forge's produced-mana string (e.g. "G", "W", "Any") to proto ManaColor. */
    internal fun producedToManaColor(produced: String): ManaColor? =
        ManaColorMapping.fromProduced(produced)

    /**
     * Strip an Action down to the minimal format used inside GSM embedded actions.
     * Real server: Cast=instanceId+manaCost, Play=instanceId, ActivateMana=instanceId+abilityGrpId.
     * No grpId, facetId, shouldStop, autoTapSolution.
     */
    fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        when (action.actionType) {
            ActionType.Cast, ActionType.CastAdventure -> {
                b.setInstanceId(action.instanceId)
                // Real server sends no manaCost in GSM actions — client derives from card DB
            }
            ActionType.Play_add3 -> b.setInstanceId(action.instanceId)
            ActionType.ActivateMana, ActionType.Activate_add3 -> {
                b.setInstanceId(action.instanceId)
                if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            }
            ActionType.Pass, ActionType.FloatMana -> {} // empty
            else -> b.setInstanceId(action.instanceId)
        }
        return b.build()
    }
}
