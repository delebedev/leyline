package leyline.game.mapping

import forge.ai.ComputerUtilMana
import forge.card.CardStateName
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.cost.CostAdjustment
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.AlternativeCost
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.chooseCastAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.codes.ManaColorMapping
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KEYWORD_BASE_IDS
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityRegistry
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Maps Forge playable actions to Arena [Action] / [ActionsAvailableReq] protos.
 *
 * Depends on [IdMapping] (instanceId allocation) and [PlayerLookup] (seat → player).
 */
@Suppress("LargeClass") // buildFromSnapshot mirrors buildActionList — inherent size; split assessed
object ActionMapper {
    private val log = LoggerFactory.getLogger(ActionMapper::class.java)

    private const val INITIAL_MANA_ID = 10

    /** Universal Arena ability id for "Cast without paying mana cost" — used as
     *  `alternativeGrpId` on cast actions for plotted / suspended / similar
     *  no-mana cast rails. */
    private const val CAST_WITHOUT_PAYING_MANA_GRP_ID = 149

    /**
     * Naive action list: Cast for all non-lands, Play for all lands in hand,
     * ActivateMana for untapped permanents — no canPlay/canPay checks.
     * Client expects human's potential actions embedded during AI turn regardless of phase.
     */
    fun buildNaiveActions(
        seatId: Int,
        bridge: GameBridge,
    ): ActionsAvailableReq = buildActionList(seatId, bridge, checkLegality = false)

    /**
     * Shared action list builder — bridge-backed overload.
     *
     * Extracts the function params the pure overload needs from [bridge] and
     * forwards. Callers that already have the discrete params should prefer
     * the pure overload directly.
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
            grpIdResolver = { card ->
                val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
                bridge.resolveGrpId(card, iid)
            },
            cardDataLookup = { grpId -> bridge.cardRepository.findByGrpId(grpId) },
            abilityRegistryLookup = { card, cardData -> bridge.abilityRegistryFor(card, cardData) },
            cardRepository = bridge.cardRepository,
        )
    }

    // -------------------------------------------------------------------------
    // Task 8: snapshot-driven overload
    // -------------------------------------------------------------------------

    /**
     * Build [ActionsAvailableReq] from a pre-captured [GsmSnapshot].
     *
     * Zone iteration and card-identity reads come from the snapshot (immutable,
     * race-free). Cost-legality checks route through [legalityFor] which reads
     * the live Forge [Card] via [bridge] — this keeps cost-solver migration
     * out of scope for this task.
     *
     * Mirrors [buildActionList] (checkLegality=true) branch by branch.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // mirrors buildActionList complexity
    fun buildFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()

        val handZoneId = ZoneIds.handOf(seatId)
        val hand = snap.zones[handZoneId]?.contents.orEmpty()
        val battlefield = snap.zones[ZoneIds.BATTLEFIELD]?.contents.orEmpty()

        // --- Battlefield: ActivateMana + Activate (own permanents only) ---
        for (fid in battlefield) {
            val card = snap.objects[fid] ?: continue
            if (card.controller.value != seatId) continue

            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = card.grpId

            if (!card.tapped && card.hasManaAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                builder.addActions(
                    buildActivateManaAction(
                        forgeCard,
                        instanceId,
                        grpId,
                        { bridge.cardRepository.findByGrpId(it) },
                        { c, d -> bridge.abilityRegistryFor(c, d) },
                    ),
                )
            }

            if (card.hasNonManaActivatedAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val player = bridge.getPlayer(SeatId(seatId)) ?: continue
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                for (ability in forgeCard.spellAbilities) {
                    ability.setActivatingPlayer(player)
                    if (!ability.isActivatedAbility) continue
                    if (ability.isManaAbility()) continue
                    if (!ability.canPlay()) continue
                    val canPay =
                        try {
                            ComputerUtilMana.canPayManaCost(ability, player, 0, false)
                        } catch (_: Exception) {
                            false
                        }
                    val registry = bridge.abilityRegistryFor(forgeCard, cardData)
                    val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                    if (canPay) {
                        val actionBuilder =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Activate_add3)
                                .setInstanceId(instanceId)
                                .setGrpId(grpId)
                                .setFacetId(instanceId)
                                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
                        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                        builder.addActions(actionBuilder)
                    } else {
                        val inactiveBuilder =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Activate_add3)
                                .setInstanceId(instanceId)
                                .setGrpId(grpId)
                                .setFacetId(instanceId)
                        if (abilityGrpId > 0) inactiveBuilder.setAbilityGrpId(abilityGrpId)
                        val abilityCost = ability.payCosts?.totalMana
                        if (abilityCost != null && !abilityCost.isNoCost) {
                            addManaCostFromForge(abilityCost, inactiveBuilder, abilityGrpId)
                        }
                        builder.addInactiveActions(inactiveBuilder)
                    }
                }
            }
        }

        // --- Hand: lands ---
        for (fid in hand) {
            val card = snap.objects[fid] ?: continue
            if (!card.isLand) continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = card.grpId
            val legality = legalityFor(seatId, fid, bridge)
            if (legality.canPlayLand) {
                builder.addActions(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)),
                )
            } else {
                builder.addInactiveActions(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setGrpId(grpId)
                        .setInstanceId(instanceId)
                        .setFacetId(instanceId),
                )
            }
        }

        // --- Hand: non-land spells (Cast + CastAdventure) ---
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.isLand) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val sa = chooseCastAbility(forgeCard, player) ?: continue
            if (hasUnmetTargeting(sa)) {
                log.trace("ActionMapper.buildFromSnapshot: skipping {} — no legal targets", cardSnap.name)
                continue
            }
            val canPay =
                try {
                    ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = cardSnap.grpId

            if (!canPay) {
                val inactiveBuilder =
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                val effectiveCost = computeEffectiveCost(sa, player)
                if (effectiveCost != null && !effectiveCost.isNoCost) {
                    addManaCostFromForge(effectiveCost, inactiveBuilder)
                } else {
                    val cardData = bridge.cardRepository.findByGrpId(grpId)
                    if (cardData != null) {
                        for ((color, count) in cardData.manaCost) {
                            inactiveBuilder.addManaCost(ManaRequirement.newBuilder().addColor(color).setCount(count))
                        }
                    }
                }
                builder.addInactiveActions(inactiveBuilder)
                addHandAltCostCastActions(
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    cardRepository = bridge.cardRepository,
                    builder = builder,
                )
                continue
            }

            val actionBuilder =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

            val effectiveCost = computeEffectiveCost(sa, player)
            if (effectiveCost != null && !effectiveCost.isNoCost) {
                addManaCostFromForge(effectiveCost, actionBuilder)
                val costPairs = forgeManaCostToPairs(effectiveCost)
                val autoTap =
                    buildAutoTapSolution(
                        costPairs,
                        player,
                        idResolver = { bridge.getOrAllocInstanceId(ForgeCardId(it)).value },
                        grpIdResolver = { c -> bridge.resolveGrpId(c, bridge.getOrAllocInstanceId(ForgeCardId(c.id)).value) },
                        cardDataLookup = { bridge.cardRepository.findByGrpId(it) },
                        abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                    )
                if (autoTap != null) actionBuilder.setAutoTapSolution(autoTap)
            } else {
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                if (cardData != null) {
                    for ((color, count) in cardData.manaCost) {
                        actionBuilder.addManaCost(ManaRequirement.newBuilder().addColor(color).setCount(count))
                    }
                }
            }
            builder.addActions(actionBuilder)

            addHandAltCostCastActions(
                card = forgeCard,
                player = player,
                instanceId = instanceId,
                grpId = grpId,
                cardRepository = bridge.cardRepository,
                builder = builder,
            )

            if (cardSnap.isAdventureCard) {
                val advAction = buildAdventureAction(forgeCard, player, instanceId, grpId, checkLegality = true)
                if (advAction != null) {
                    builder.addActions(advAction)
                } else {
                    buildInactiveAdventureAction(forgeCard, player, instanceId, grpId)
                        ?.let { builder.addInactiveActions(it) }
                }
            }
        }

        // --- Hand: non-battlefield activated abilities (Channel, Ninjutsu, etc.) ---
        // Plot is intentionally NOT here — Plot's hand SA rides the Cast-with-alt-cost
        // rail via [addHandAltCostCastActions] (mirroring Warp / Sneak).
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.hasNonManaActivatedAbilities) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            for (ability in forgeCard.spellAbilities) {
                ability.setActivatingPlayer(player)
                if (!ability.isActivatedAbility) continue
                if (ability.isManaAbility()) continue
                if (!ability.canPlay()) continue
                val canPay =
                    try {
                        ComputerUtilMana.canPayManaCost(ability, player, 0, false)
                    } catch (_: Exception) {
                        false
                    }
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                val grpId = cardSnap.grpId
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                val registry = bridge.abilityRegistryFor(forgeCard, cardData)
                val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                val abilityCost = ability.payCosts?.totalMana
                if (canPay) {
                    val actionBuilder =
                        Action
                            .newBuilder()
                            .setActionType(ActionType.Activate_add3)
                            .setInstanceId(instanceId)
                    if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                    if (abilityCost != null && !abilityCost.isNoCost) {
                        addManaCostFromForge(abilityCost, actionBuilder, abilityGrpId)
                    }
                    builder.addActions(actionBuilder)
                } else {
                    val inactiveBuilder =
                        Action
                            .newBuilder()
                            .setActionType(ActionType.Activate_add3)
                            .setInstanceId(instanceId)
                    if (abilityGrpId > 0) inactiveBuilder.setAbilityGrpId(abilityGrpId)
                    if (abilityCost != null && !abilityCost.isNoCost) {
                        addManaCostFromForge(abilityCost, inactiveBuilder, abilityGrpId)
                    }
                    builder.addInactiveActions(inactiveBuilder)
                }
            }
        }

        // --- Zone casts (graveyard, exile, command) ---
        addZoneCastActionsFromSnap(seatId, snap, builder, bridge)

        // Pass + FloatMana always available
        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        val manaCount = builder.actionsList.count { it.actionType == ActionType.ActivateMana }
        val landCount = builder.actionsList.count { it.actionType == ActionType.Play_add3 }
        val castCount = builder.actionsList.count { it.actionType == ActionType.Cast }
        val activateCount = builder.actionsList.count { it.actionType == ActionType.Activate_add3 }
        val inactiveCount = builder.inactiveActionsCount
        log.debug(
            "buildFromSnapshot: seat={} mana={} activate={} lands={} casts={} inactive={} total={}",
            seatId,
            manaCount,
            activateCount,
            landCount,
            castCount,
            inactiveCount,
            builder.actionsCount,
        )

        return builder.build()
    }

    /**
     * Cost-legality result for one card in the active player's hand/battlefield.
     * The shim reads the live Forge [Card] and runs the same canPlayLand logic
     * [buildActionList] uses — insulating the snapshot path from cost-solver migration.
     */
    private data class LegalityResult(
        val canPlayLand: Boolean,
    )

    /**
     * Route cost/playability checks through live Forge — the snapshot path
     * delegates all Forge cost-solver calls here.
     */
    private fun legalityFor(
        seatId: Int,
        fid: ForgeCardId,
        bridge: GameBridge,
    ): LegalityResult {
        val forgeCard = bridge.findCard(fid) ?: return LegalityResult(canPlayLand = false)
        val player = bridge.getPlayer(SeatId(seatId)) ?: return LegalityResult(canPlayLand = false)
        val landAbility = LandAbility(forgeCard, forgeCard.currentState)
        landAbility.activatingPlayer = player
        return LegalityResult(
            canPlayLand = player.canPlayLand(forgeCard, false, landAbility),
        )
    }

    /**
     * Zone casts (graveyard, exile, command) using snapshot zone contents + live Forge legality.
     * Mirrors [addZoneCastActions] but pulls card IDs from the snapshot.
     */
    private fun addZoneCastActionsFromSnap(
        seatId: Int,
        snap: GsmSnapshot,
        builder: ActionsAvailableReq.Builder,
        bridge: GameBridge,
    ) {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return
        val zoneIds = listOf(ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD, ZoneIds.EXILE, ZoneIds.COMMAND)
        for (zoneId in zoneIds) {
            val zone = snap.zones[zoneId] ?: continue
            for (fid in zone.contents) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val castable = getAllCastableAbilities(forgeCard, player)
                if (castable.isEmpty()) continue
                val sa = castable.first()
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                val grpId =
                    snap.objects[fid]?.grpId
                        ?: bridge.resolveGrpId(forgeCard, instanceId)
                val altCost = sa.alternativeCost
                val isPlottedCast = altCost == AlternativeCost.Plotted
                val isForetellCast = altCost == AlternativeCost.Foretold
                val isDisturbCast = altCost == AlternativeCost.Disturb
                val isEscapeCast = altCost == AlternativeCost.Escape
                val isMinimalEmit = isPlottedCast || isForetellCast || isEscapeCast
                val actionBuilder =
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

                // Plotted and foretold cast-from-exile use a minimal action shape —
                // no grpId / facetId. The canonical action carries only instanceId
                // + abilityGrpId/alternativeGrpId for the keyword + manaCost (where
                // applicable). Including grpId/facetId here makes MTGA treat the
                // cast as a regular cast and the alt-cost branch never lands.
                //
                // Disturb keeps the front-face grpId on the offer — the card IS
                // front-face in the graveyard. The back-face transform happens on
                // cast acceptance (Forge sets currentState=AlternateState, the new
                // Stack object then resolves to back-face grpId via ObjectMapper).
                if (!isMinimalEmit) {
                    actionBuilder.setGrpId(grpId)
                    actionBuilder.setFacetId(instanceId)
                }

                val cardData = bridge.cardRepository.findByGrpId(grpId)
                // Resolve the foretell ability id once — used both as alternativeGrpId
                // on the action and as the abilityGrpId echo inside each ManaRequirement.
                val foretellAbilityGrpId =
                    if (isForetellCast) {
                        bridge.cardRepository.findKeywordAbilityGrpId(grpId, "FORETELL") ?: 0
                    } else {
                        0
                    }
                val escapeAbilityGrpId =
                    if (isEscapeCast) {
                        bridge.cardRepository.findKeywordAbilityGrpId(grpId, "ESCAPE") ?: 0
                    } else {
                        0
                    }
                if (isPlottedCast) {
                    actionBuilder.setAlternativeGrpId(CAST_WITHOUT_PAYING_MANA_GRP_ID)
                    actionBuilder.setAbilityGrpId(KEYWORD_BASE_IDS.getValue("PLOTTED"))
                } else if (isForetellCast) {
                    // Foretell cast-from-exile uses the type=13 CastingTimeOption rail
                    // with the per-card foretell ability id as alternativeGrpId.
                    // No top-level abilityGrpId — the foretell BaseId (208) is universal,
                    // not per-card; the per-card row id is what the action needs.
                    if (foretellAbilityGrpId > 0) actionBuilder.setAlternativeGrpId(foretellAbilityGrpId)
                } else if (isDisturbCast) {
                    // Disturb cast: both alternativeGrpId AND abilityGrpId carry the
                    // per-card disturb ability id (BaseId=215 chain). Mirrors the
                    // UserActionTaken shape that the cast resolves to.
                    val disturbAbilityGrpId =
                        bridge.cardRepository.findKeywordAbilityGrpId(grpId, "DISTURB") ?: 0
                    if (disturbAbilityGrpId > 0) {
                        actionBuilder.setAlternativeGrpId(disturbAbilityGrpId)
                        actionBuilder.setAbilityGrpId(disturbAbilityGrpId)
                    }
                } else if (isEscapeCast) {
                    // Escape cast: same minimal-emit shape as Plot/Foretell — both
                    // alternativeGrpId and abilityGrpId carry the per-card escape
                    // ability id (BaseId=199 chain). The {N other cards} additional
                    // cost is paid via Forge's Cost.payAdditionalCosts pipeline,
                    // which surfaces as a separate exile-N selection prompt.
                    if (escapeAbilityGrpId > 0) {
                        actionBuilder.setAlternativeGrpId(escapeAbilityGrpId)
                        actionBuilder.setAbilityGrpId(escapeAbilityGrpId)
                    }
                } else if (altCost != null) {
                    // TODO(leyline-9n6): extend KEYWORD_BASE_IDS for Mayhem/etc.
                    val altCostName = altCost.name.uppercase()
                    val abilityGrpId =
                        bridge.cardRepository.findKeywordAbilityGrpId(grpId, altCostName) ?: 0
                    if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                }

                val effectiveCost = computeEffectiveCost(sa, player)
                if (effectiveCost != null && !effectiveCost.isNoCost) {
                    if (isForetellCast && foretellAbilityGrpId > 0) {
                        addManaCostFromForge(effectiveCost, actionBuilder, foretellAbilityGrpId)
                    } else if (isEscapeCast && escapeAbilityGrpId > 0) {
                        addManaCostFromForge(effectiveCost, actionBuilder, escapeAbilityGrpId)
                    } else {
                        addManaCostFromForge(effectiveCost, actionBuilder)
                    }
                } else if (altCost == null && cardData != null) {
                    // Fallback to printed mana cost only when there's no alt-cost
                    // SA. AlternativeCost.Plotted (cast plotted card from exile)
                    // and similar copyWithNoManaCost rails have isNoCost==true and
                    // must NOT inherit the printed cost.
                    for ((color, count) in cardData.manaCost) {
                        actionBuilder.addManaCost(ManaRequirement.newBuilder().addColor(color).setCount(count))
                    }
                }
                builder.addActions(actionBuilder)
            }
        }
    }

    // -------------------------------------------------------------------------
    // End Task 8
    // -------------------------------------------------------------------------

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
        cardRepository: CardRepository? = null,
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
                    val canPay =
                        try {
                            ComputerUtilMana.canPayManaCost(ability, player, 0, false)
                        } catch (_: Exception) {
                            false
                        }
                    val registry = abilityRegistryLookup(card, cardData)
                    val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                    if (canPay) {
                        val actionBuilder =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Activate_add3)
                                .setInstanceId(instanceId)
                                .setGrpId(grpId)
                                .setFacetId(instanceId)
                                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
                        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                        builder.addActions(actionBuilder)
                    } else {
                        val inactiveBuilder =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Activate_add3)
                                .setInstanceId(instanceId)
                                .setGrpId(grpId)
                                .setFacetId(instanceId)
                        if (abilityGrpId > 0) inactiveBuilder.setAbilityGrpId(abilityGrpId)
                        val abilityCost = ability.payCosts?.totalMana
                        if (abilityCost != null && !abilityCost.isNoCost) {
                            addManaCostFromForge(abilityCost, inactiveBuilder, abilityGrpId)
                        }
                        builder.addInactiveActions(inactiveBuilder)
                    }
                }
            }
        }

        // Hand cards: Lands + Spells
        val handCards = player.getZone(ForgeZoneType.Hand).cards

        // Lands: playable → actions, not playable → inactiveActions (legality only)
        for (card in CardLists.filter(handCards, CardPredicates.LANDS)) {
            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)
            val canPlay =
                if (checkLegality) {
                    val landAbility = LandAbility(card, card.currentState)
                    landAbility.activatingPlayer = player
                    player.canPlayLand(card, false, landAbility)
                } else {
                    false
                }
            if (canPlay) {
                builder.addActions(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)),
                )
            } else {
                // Greyed-out: land can't be played (already played one this turn)
                builder.addInactiveActions(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Play_add3)
                        .setGrpId(grpId)
                        .setInstanceId(instanceId)
                        .setFacetId(instanceId),
                )
            }
        }

        // Non-land spells (Cast before Activate_add3 — client uses emission order for text assignment)
        for (card in CardLists.filter(handCards, CardPredicates.NON_LANDS)) {
            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)
            val (actions, inactive) =
                buildHandCastActionsForCard(
                    card = card,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    checkLegality = checkLegality,
                    idResolver = idResolver,
                    grpIdResolver = grpIdResolver,
                    cardDataLookup = cardDataLookup,
                    abilityRegistryLookup = abilityRegistryLookup,
                )
            actions.forEach(builder::addActions)
            inactive.forEach(builder::addInactiveActions)

            if (checkLegality) {
                addHandAltCostCastActions(
                    card = card,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    cardRepository = cardRepository,
                    builder = builder,
                )
            }

            // CastAdventure for adventure-capable cards
            if (card.isAdventureCard) {
                val advAction = buildAdventureAction(card, player, instanceId, grpId, checkLegality)
                if (advAction != null) {
                    builder.addActions(advAction)
                } else if (checkLegality) {
                    buildInactiveAdventureAction(card, player, instanceId, grpId)
                        ?.let { builder.addInactiveActions(it) }
                }
            }
        }

        // Hand cards: activated abilities with non-battlefield activation zones (Channel, etc.)
        // Plot is intentionally NOT here — see addHandAltCostCastActions for the Plot rail.
        // Client expects: instanceId + abilityGrpId + manaCost — no grpId/facetId.
        // Including grpId causes the client to render card text instead of ability text.
        if (checkLegality) {
            for (card in handCards) {
                for (ability in card.spellAbilities) {
                    ability.setActivatingPlayer(player)
                    if (!ability.isActivatedAbility) continue
                    if (ability.isManaAbility()) continue
                    if (!ability.canPlay()) continue // Forge checks ActivationZone restriction
                    val canPay =
                        try {
                            ComputerUtilMana.canPayManaCost(ability, player, 0, false)
                        } catch (_: Exception) {
                            false
                        }
                    val instanceId = idResolver(card.id)
                    val grpId = grpIdResolver(card)
                    val cardData = cardDataLookup(grpId)
                    val registry = abilityRegistryLookup(card, cardData)
                    val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                    val abilityCost = ability.payCosts?.totalMana
                    if (canPay) {
                        val actionBuilder =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Activate_add3)
                                .setInstanceId(instanceId)
                        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
                        // Wire requires manaCost with abilityGrpId echoed in each ManaRequirement
                        if (abilityCost != null && !abilityCost.isNoCost) {
                            addManaCostFromForge(abilityCost, actionBuilder, abilityGrpId)
                        }
                        builder.addActions(actionBuilder)
                    } else {
                        val inactiveBuilder =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Activate_add3)
                                .setInstanceId(instanceId)
                        if (abilityGrpId > 0) inactiveBuilder.setAbilityGrpId(abilityGrpId)
                        if (abilityCost != null && !abilityCost.isNoCost) {
                            addManaCostFromForge(abilityCost, inactiveBuilder, abilityGrpId)
                        }
                        builder.addInactiveActions(inactiveBuilder)
                    }
                }
            }
        }

        // Zone casts: Graveyard, Exile, Command (flashback, escape, etc.)
        if (checkLegality) {
            addZoneCastActions(player, builder, idResolver, grpIdResolver, cardDataLookup, cardRepository)
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
            val inactiveCount = builder.inactiveActionsCount
            log.debug(
                "buildActions: seat={} mana={} activate={} lands={} casts={} inactive={} total={}",
                seatId,
                manaCount,
                activateCount,
                landCount,
                castCount,
                inactiveCount,
                builder.actionsCount,
            )
        } else {
            log.debug(
                "buildNaiveActions: seat={} mana={} lands={} casts={} total={}",
                seatId,
                manaCount,
                landCount,
                castCount,
                builder.actionsCount,
            )
        }

        return builder.build()
    }

    internal fun buildHandCastActionsForCard(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        checkLegality: Boolean,
        idResolver: (Int) -> Int,
        grpIdResolver: (Card) -> Int,
        cardDataLookup: (Int) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry? = { _, _ -> null },
    ): Pair<List<Action>, List<Action>> {
        val cardData = cardDataLookup(grpId)
        if (!checkLegality) {
            return listOf(buildFallbackCastAction(instanceId, grpId, cardData)) to emptyList()
        }

        val actions = mutableListOf<Action>()
        val inactive = mutableListOf<Action>()
        val castable = getAllCastableAbilities(card, player)
        if (castable.isEmpty()) return emptyList<Action>() to emptyList()

        for (sa in castable) {
            if (sa.isAdventure) continue
            if (hasUnmetTargeting(sa)) {
                log.debug("ActionMapper: skipping {} variant — no legal targets", card.name)
                continue
            }
            val canPay =
                try {
                    ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
            val action =
                buildCastAction(
                    sa = sa,
                    instanceId = instanceId,
                    grpId = grpId,
                    player = player,
                    checkLegality = checkLegality,
                    idResolver = idResolver,
                    grpIdResolver = grpIdResolver,
                    cardData = cardData,
                    cardDataLookup = cardDataLookup,
                    abilityRegistryLookup = abilityRegistryLookup,
                )
            if (canPay) actions.add(action) else inactive.add(action)
        }
        return actions to inactive
    }

    @Suppress("LongParameterList")
    private fun buildCastAction(
        sa: SpellAbility,
        instanceId: Int,
        grpId: Int,
        player: Player,
        checkLegality: Boolean,
        idResolver: (Int) -> Int,
        grpIdResolver: (Card) -> Int,
        cardData: CardData?,
        cardDataLookup: (Int) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): Action {
        val usesAlternateAdditionalCost =
            sa.hostCard?.keywords?.any {
                it.original.startsWith("AlternateAdditionalCost")
            } == true &&
                (sa.description?.contains("Additional cost:") == true)
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.Cast)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

        val effectiveCost = computeEffectiveCost(sa, player)
        val displayCost = if (usesAlternateAdditionalCost) null else effectiveCost
        if (displayCost != null && !displayCost.isNoCost) {
            addManaCostFromForge(displayCost, actionBuilder)
        } else if (cardData != null) {
            for ((color, count) in cardData.manaCost) {
                actionBuilder.addManaCost(
                    ManaRequirement.newBuilder().addColor(color).setCount(count),
                )
            }
        }
        if (effectiveCost != null && !effectiveCost.isNoCost && checkLegality) {
            val costPairs = forgeManaCostToPairs(effectiveCost)
            val autoTap = buildAutoTapSolution(costPairs, player, idResolver, grpIdResolver, cardDataLookup, abilityRegistryLookup)
            if (autoTap != null) actionBuilder.setAutoTapSolution(autoTap)
        }
        return actionBuilder.build()
    }

    private fun buildFallbackCastAction(
        instanceId: Int,
        grpId: Int,
        cardData: CardData?,
    ): Action =
        Action
            .newBuilder()
            .setActionType(ActionType.Cast)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
            .apply {
                if (cardData != null) {
                    for ((color, count) in cardData.manaCost) {
                        addManaCost(ManaRequirement.newBuilder().addColor(color).setCount(count))
                    }
                }
            }.build()

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
        val mana = sa.manaPart
        val produced = if (mana != null && mana.isComboMana) mana.getComboColors(sa) else mana?.origProduced.orEmpty()
        val manaColor = produced.split(" ").firstNotNullOfOrNull { producedToManaColor(it) } ?: ManaColor.Generic

        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.ActivateMana)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .setIsBatchable(true)
        if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)

        actionBuilder.addManaPaymentOptions(
            ManaPaymentOption.newBuilder().addMana(
                ManaInfo
                    .newBuilder()
                    .setManaId(10)
                    .setColor(manaColor)
                    .setSrcInstanceId(instanceId)
                    .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                    .setAbilityGrpId(abilityGrpId)
                    .setCount(1),
            ),
        )

        actionBuilder.addManaSelections(
            ManaSelection
                .newBuilder()
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
            val canCast =
                try {
                    adventureSa.canPlay() && ComputerUtilMana.canPayManaCost(adventureSa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
            if (!canCast) return null
        }

        // grpId = creature face — client can't resolve IsPrimaryCard=0 adventure
        // faces and rejects the action if grpId is unknown. manaCost from the
        // adventure SA provides the correct cost for the Choose One modal.
        val builder =
            Action
                .newBuilder()
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

    /** Build an inactive CastAdventure action (unaffordable), or null if card has no adventure state. */
    private fun buildInactiveAdventureAction(
        card: Card,
        player: Player,
        instanceId: Int,
        creatureGrpId: Int,
    ): Action? {
        val adventureState = card.getState(CardStateName.Secondary) ?: return null
        val adventureSa = adventureState.nonManaAbilities?.firstOrNull() ?: return null
        adventureSa.setActivatingPlayer(player)
        // Only emit inactive if the adventure is legal but unaffordable
        if (!adventureSa.canPlay()) return null
        val builder =
            Action
                .newBuilder()
                .setActionType(ActionType.CastAdventure)
                .setInstanceId(instanceId)
                .setGrpId(creatureGrpId)
        val advEffective = computeEffectiveCost(adventureSa, player)
        if (advEffective != null && !advEffective.isNoCost) {
            addManaCostFromForge(advEffective, builder)
        } else {
            val advManaCost = adventureSa.payCosts?.totalMana
            if (advManaCost != null && !advManaCost.isNoCost) {
                addManaCostFromForge(advManaCost, builder)
            }
        }
        return builder.build()
    }

    /**
     * Hand-zone alt-cost casts (Warp, Sneak) — emit one [Action] per eligible alt-cost SA.
     *
     * Wire shape per captured Arena recordings:
     *  - `instanceId` = hand card iid, `grpId` = card grpId, `facetId` = iid
     *  - `abilityGrpId` = 0 (intentionally — the alt-cost row is carried on
     *    `alternativeGrpId`, not here)
     *  - `alternativeGrpId` = per-card warp/sneak ability grpId (keyword→grpId lookup)
     *  - `manaCost` entries echo `alternativeGrpId` on each slot (so the client
     *    associates the cost display with the alt-cost row)
     *
     * Scoped to Warp/Sneak intentionally — other alt-costs (Madness, Flashback,
     * Impending, …) are surfaced via their existing rails (OptionalAction for
     * Madness, zone-cast for Flashback). Widening this path without corpus
     * evidence risks double-offer regressions.
     */
    private fun addHandAltCostCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        cardRepository: CardRepository?,
        builder: ActionsAvailableReq.Builder,
    ) {
        // getAllCastableAbilities now includes plot + foretell SAs (CardLookup.kt) so
        // a single iteration covers Warp / Sneak / Plot / Foretell.
        val castable = getAllCastableAbilities(card, player)
        for (sa in castable) {
            val altCost = sa.alternativeCost
            val isKeywordHandSA = sa.isPlotting || sa.isForetelling
            if (altCost != AlternativeCost.Warp && altCost != AlternativeCost.Sneak && !isKeywordHandSA) continue
            val canPay =
                try {
                    ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
            if (!canPay) continue

            val effectiveCost = computeEffectiveCost(sa, player)
            // Resolve the per-card warp/sneak/plot/foretell row.
            //
            // For Warp/Sneak/Plot the hand SA's mana cost == the alt-cost row's
            // mana cost (e.g. Plot {3}{G} hand SA pays {3}{G}, row is {3}{G}),
            // so cost-aware findAlternativeCostAbilityGrpId matches cleanly.
            //
            // For Foretell the hand SA's cost is the constant {2} (the foretell
            // *action* cost), but the per-card row's mana cost is the foretell
            // *cast* cost ({R} for Demon Bolt). Cost-aware lookup misses. Fall
            // back to cost-agnostic findKeywordAbilityGrpId for foretell — there
            // is at most one FORETELL row per card.
            val payCostPairs: List<Pair<ManaColor, Int>> =
                effectiveCost?.takeIf { !it.isNoCost }?.let { forgeManaCostToPairs(it) } ?: emptyList()
            val altCostKey =
                when {
                    sa.isPlotting -> "PLOTTED"
                    sa.isForetelling -> "FORETELL"
                    else -> altCost!!.name.uppercase()
                }
            val alternativeGrpId =
                if (sa.isForetelling) {
                    cardRepository?.findKeywordAbilityGrpId(grpId, altCostKey) ?: 0
                } else {
                    cardRepository?.findAlternativeCostAbilityGrpId(grpId, altCostKey, payCostPairs) ?: 0
                }
            if (alternativeGrpId <= 0) continue

            val actionBuilder =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setAlternativeGrpId(alternativeGrpId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

            if (effectiveCost != null && !effectiveCost.isNoCost) {
                addManaCostFromForge(effectiveCost, actionBuilder, alternativeGrpId)
            }
            builder.addActions(actionBuilder)
        }
    }

    private fun addZoneCastActions(
        player: Player,
        builder: ActionsAvailableReq.Builder,
        idResolver: (Int) -> Int,
        grpIdResolver: (Card) -> Int,
        cardDataLookup: (Int) -> CardData?,
        cardRepository: CardRepository?,
    ) {
        val game = player.game ?: return
        val zones = listOf(ForgeZoneType.Graveyard, ForgeZoneType.Exile, ForgeZoneType.Command)
        for (card in game.getCardsIn(zones)) {
            val castable = getAllCastableAbilities(card, player)
            if (castable.isEmpty()) continue
            val sa = castable.first()

            val instanceId = idResolver(card.id)
            val grpId = grpIdResolver(card)
            val actionBuilder =
                Action
                    .newBuilder()
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
                // TODO(leyline-9n6): Warp/Sneak/Flashback resolve via KEYWORD_BASE_IDS;
                //   Escape/Mayhem/Commander etc. need BaseIds populated once verified
                //   against recordings. Until then those keywords return null here.
                val abilityGrpId =
                    cardRepository?.findKeywordAbilityGrpId(grpId, altCostName) ?: 0
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
        ActionsAvailableReq
            .newBuilder()
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

        data class ManaSource(
            val card: Card,
            val instanceId: Int,
            val color: ManaColor,
            val abilityGrpId: Int,
        )

        // Collect untapped mana sources with their produced color
        val sources = mutableListOf<ManaSource>()
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            for (sa in card.manaAbilities) {
                sa.setActivatingPlayer(player)
                if (!sa.canPlay()) continue
                val mana = sa.manaPart ?: continue
                val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
                val colors = produced.split(" ").mapNotNull { producedToManaColor(it) }
                if (colors.isEmpty()) continue
                val instanceId = idResolver(card.id)
                val grpId = grpIdResolver(card)
                val cardData = cardDataLookup(grpId)
                val registry = abilityRegistryLookup(card, cardData)
                val abilityGrpId = registry?.forSpellAbility(sa.id) ?: 0
                for (color in colors) {
                    sources.add(ManaSource(card, instanceId, color, abilityGrpId))
                }
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

        // Build AutoTapSolution matching expected protocol format:
        // Each AutoTapAction has manaPaymentOption with full ManaInfo
        val builder = AutoTapSolution.newBuilder()
        var manaIdCounter = INITIAL_MANA_ID
        for ((src, payingColor) in matched) {
            val manaId = manaIdCounter++
            builder.addAutoTapActions(
                AutoTapAction
                    .newBuilder()
                    .setInstanceId(src.instanceId)
                    .setAbilityGrpId(src.abilityGrpId)
                    .setManaPaymentOption(
                        ManaPaymentOption.newBuilder().addMana(
                            ManaInfo
                                .newBuilder()
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
    internal fun computeEffectiveCost(
        sa: SpellAbility,
        player: Player,
    ): forge.card.mana.ManaCost? {
        val baseCost = sa.payCosts ?: return null
        val adjusted = CostAdjustment.adjust(baseCost, sa, false)
        val manaCost = adjusted.totalMana ?: return null
        if (manaCost.isNoCost) return null
        val beingPaid = ManaCostBeingPaid(manaCost)
        CostAdjustment.adjust(beingPaid, sa, player, null, true, false)
        return beingPaid.toManaCost()
    }

    /** Aggregate colored shards from a Forge [ManaCost] into a color→count map. */
    private fun manaCostColorCounts(manaCost: forge.card.mana.ManaCost): Map<ManaColor, Int> {
        val counts = mutableMapOf<ManaColor, Int>()
        for (shard in manaCost) {
            val color = producedToManaColor(shard.toString().removeSurrounding("{", "}")) ?: continue
            counts[color] = (counts[color] ?: 0) + 1
        }
        return counts
    }

    /**
     * Convert a Forge [ManaCost][forge.card.mana.ManaCost] to `List<Pair<ManaColor, Int>>`
     * for use with [buildAutoTapSolution] which expects that format.
     */
    internal fun forgeManaCostToPairs(manaCost: forge.card.mana.ManaCost): List<Pair<ManaColor, Int>> {
        val result = manaCostColorCounts(manaCost).map { (color, count) -> color to count }.toMutableList()
        val generic = manaCost.genericCost
        if (generic > 0) {
            result.add(ManaColor.Generic to generic)
        }
        return result
    }

    /**
     * Convert a Forge [ManaCost] into proto [ManaRequirement] entries on an action builder.
     *
     * When [abilityGrpId] is set, each [ManaRequirement] embeds it — client expects this
     * for hand-zone activated abilities (Channel, Ninjutsu, etc.) so the client can associate
     * cost display with the specific ability modal option.
     */
    private fun addManaCostFromForge(
        manaCost: forge.card.mana.ManaCost,
        actionBuilder: Action.Builder,
        abilityGrpId: Int? = null,
    ) {
        for ((color, count) in manaCostColorCounts(manaCost)) {
            val req = ManaRequirement.newBuilder().addColor(color).setCount(count)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            actionBuilder.addManaCost(req)
        }
        val generic = manaCost.genericCost
        if (generic > 0) {
            val req = ManaRequirement.newBuilder().addColor(ManaColor.Generic).setCount(generic)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            actionBuilder.addManaCost(req)
        }
    }

    /** Map Forge's produced-mana string (e.g. "G", "W", "Any") to proto ManaColor. */
    internal fun producedToManaColor(produced: String): ManaColor? = ManaColorMapping.fromProduced(produced)

    /**
     * Strip an Action down to the minimal format used inside GSM embedded actions.
     *
     * GSM actions carry fewer fields than ActionsAvailableReq actions:
     * - Cast/CastAdventure: instanceId + manaCost
     * - Play: instanceId
     * - ActivateMana/Activate: instanceId + abilityGrpId
     * - Pass/FloatMana: empty
     *
     * No grpId, facetId, shouldStop, or autoTapSolution.
     */
    fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        when (action.actionType) {
            ActionType.Cast, ActionType.CastAdventure -> {
                b.setInstanceId(action.instanceId)
                b.addAllManaCost(action.manaCostList)
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

    /**
     * True if any ability in the SA chain requires targets and has no legal candidates.
     *
     * Special case: Forge's [TargetRestrictions.hasCandidates] short-circuits to true
     * for stack-zone targets without checking stack contents. We override that for
     * spells targeting the stack (counterspells) — check stack emptiness directly.
     */
    private fun hasUnmetTargeting(sa: SpellAbility): Boolean {
        val game = sa.hostCard?.game ?: return false
        var node: SpellAbility? = sa
        while (node != null) {
            val tr = node.targetRestrictions
            if (tr != null) {
                if (tr.zone.contains(forge.game.zone.ZoneType.Stack)) {
                    if (game.stack.isEmpty) return true
                } else if (!tr.hasCandidates(node)) {
                    return true
                }
            }
            node = node.subAbility
        }
        return false
    }
}
