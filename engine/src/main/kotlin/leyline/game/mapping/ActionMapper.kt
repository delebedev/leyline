package leyline.game.mapping

import forge.ai.ComputerUtilMana
import forge.card.CardStateName
import forge.card.mana.ManaCost
import forge.game.ability.ApiType
import forge.game.ability.effects.CharmEffect
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.chooseCastAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.AltCostBinding
import leyline.game.snapshot.BoundCard
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
@Suppress("LargeClass") // action emission spans multiple zones and wire shapes.
object ActionMapper {
    private val log = LoggerFactory.getLogger(ActionMapper::class.java)

    data class IndexedCastAction(
        val abilityIndex: Int,
        val action: Action,
    )

    private fun canPayManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean = ActionManaCosts.canPayManaCost(sa, player)

    private fun canPlayAndPayManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean = ActionManaCosts.canPlayAndPayManaCost(sa, player)

    /**
     * Naive action list: Cast for all non-lands, Play for all lands in hand,
     * ActivateMana for untapped permanents — no canPlay/canPay checks.
     * Client expects human's potential actions embedded during AI turn regardless of phase.
     */
    fun buildNaiveActions(
        seatId: Int,
        bridge: GameBridge,
    ): ActionsAvailableReq {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return passOnlyActions()
        return buildActionList(
            player = player,
            seatId = seatId,
            checkLegality = false,
            idResolver = { forgeCardId -> bridge.getOrAllocInstanceId(forgeCardId) },
            grpIdResolver = { card ->
                val iid = bridge.getOrAllocInstanceId(ForgeCardId(card.id)).value
                GrpId(bridge.resolveGrpId(card, iid))
            },
            cardDataLookup = { grpId -> bridge.cardRepository.findByGrpId(grpId.value) },
            abilityRegistryLookup = { card, cardData -> bridge.abilityRegistryFor(card, cardData) },
            cardRepository = bridge.cardRepository,
        )
    }

    /**
     * Build [ActionsAvailableReq] from a pre-captured [GsmSnapshot].
     *
     * Zone iteration and card-identity reads come from the snapshot (immutable,
     * race-free). Cost-legality checks route through [legalityFor] which reads
     * the live Forge [Card] via [bridge] — this keeps cost-solver migration
     * out of scope for this task.
     * This is the production action-emission path. The live [buildActionList]
     * overload remains as a focused test/naive-action helper and does not emit
     * zone-cast rail shapes.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // action types × zone-specific wire shapes.
    fun buildFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()

        val handZoneId = ZoneIds.handOf(seatId)
        val hand = snap.zones[handZoneId]?.contents.orEmpty()
        val battlefield = snap.zones[ZoneIds.BATTLEFIELD]?.contents.orEmpty()

        fun autoTapForCost(
            player: Player,
            cost: ManaCost,
        ): AutoTapSolution? =
            buildAutoTapSolution(
                cost,
                player,
                idResolver = { forgeCardId -> bridge.getOrAllocInstanceId(forgeCardId) },
                grpIdResolver = { c -> GrpId(bridge.resolveGrpId(c, bridge.getOrAllocInstanceId(ForgeCardId(c.id)).value)) },
                cardDataLookup = { bridge.cardRepository.findByGrpId(it.value) },
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
            )

        // --- Battlefield: ActivateMana + Activate (own permanents only) ---
        for (fid in battlefield) {
            val card = snap.objects[fid] ?: continue
            if (card.controller.value != seatId) continue

            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = card.grpId

            if (!card.tapped && card.hasManaAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val boundData = snap.boundCards[fid]?.data
                builder.addAllActions(
                    ActivatedActionEmitter.buildActivateManaAction(
                        forgeCard,
                        instanceId,
                        grpId,
                        { boundData },
                        { c, d -> bridge.abilityRegistryFor(c, d) },
                    ),
                )
            }

            if (card.hasNonManaActivatedAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val player = bridge.getPlayer(SeatId(seatId)) ?: continue
                val cardData = snap.boundCards[fid]?.data
                ActivatedActionEmitter.emitPlayableNonManaActivatedAbilities(
                    builder = builder,
                    card = forgeCard,
                    player = player,
                    instanceId = { instanceId },
                    grpId = { grpId },
                    cardData = { _ -> cardData },
                    envelope = ActivatedActionEmitter.Envelope.PERMANENT_SOURCE,
                    abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                    autoTapSolution = { cost -> autoTapForCost(player, cost) },
                    skipDisguiseTurnFaceUp = true,
                )
            }
        }

        // --- Battlefield: room door casts (for the unlocked-door's locked sibling) ---
        // A bf Room with one door already unlocked still offers CastLeftRoom /
        // CastRightRoom for the still-locked door. Stack/resolve runs the same
        // way as a hand cast — the cast emits a new stack iid that resolves
        // back onto the same bf room. Once both doors are unlocked
        // `getLockedRooms()` returns empty and no offer fires.
        for (fid in battlefield) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.controller.value != seatId) continue
            if (!cardSnap.isRoom) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            if (forgeCard.lockedRooms.isEmpty()) continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            addRoomCastActions(forgeCard, player, instanceId, builder, checkLegality = true)
        }

        // --- Battlefield: Special_TurnFaceUp for face-down disguise creatures ---
        // A controller's face-down disguise permanent surfaces a dedicated
        // Special_TurnFaceUp_add3 action carrying the per-card "Turn face up"
        // ability grpId on `alternativeGrpId` and the printed disguise cost
        // as `manaCost`. Distinct from `Activate_add3` — the client routes
        // it through a different UI flow (card-flip animation).
        for (fid in battlefield) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.controller.value != seatId) continue
            if (!cardSnap.isFaceDownDisguise) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val cardData = snap.boundCards[fid]?.data
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            addSpecialTurnFaceUpActions(
                card = forgeCard,
                player = player,
                instanceId = instanceId,
                cardData = cardData,
                fallbackAlternativeGrpId =
                    bridge.cardRepository.findKeywordAbilityGrpId(
                        cardSnap.grpId,
                        leyline.game.data.KeywordAbilityIds.DISGUISE,
                    ) ?: 0,
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                builder = builder,
            )
        }
        // --- Hand: lands ---
        for (fid in hand) {
            val card = snap.objects[fid] ?: continue
            if (!card.isLand) continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = card.grpId
            val legality = legalityFor(seatId, fid, bridge)
            emitPlayLandAction(builder, instanceId, grpId, legality.canPlayLand)
        }

        // --- Hand: non-land spells (Cast + CastAdventure) ---
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.isLand) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            // Rooms ride a dedicated CastLeftRoom / CastRightRoom rail handled
            // below — they have no plain `Cast` offer. The unlock SAs are part
            // of `getAllCastableAbilities` (so cast index resolution works),
            // but the hand-cast emit must skip them.
            if (cardSnap.isRoom) {
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                addRoomCastActions(forgeCard, player, instanceId, builder, checkLegality = true)
                continue
            }
            val sa = chooseCastAbility(forgeCard, player) ?: continue
            val noLegalTargets = hasUnmetTargeting(sa) || hasNoLegalCharmModes(sa)
            val canPay = canPayManaCost(sa, player)
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = cardSnap.grpId
            val preferAltCostFirst = getAllCastableAbilities(forgeCard, player).any { it.isCastFaceDown }

            if (preferAltCostFirst) {
                // The face-cast modal defaults to the first Cast offer even
                // when the visual click lands on the second card. Put Disguise's
                // face-down option first so the modal commit submits the
                // `alternativeGrpId=307` action instead of the printed spell.
                addHandAltCostCastActions(
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                    builder = builder,
                )
            }

            if (noLegalTargets || !canPay) {
                val inactiveBuilder =
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                val cardData = snap.boundCards[fid]?.data
                if (!usesPaymentSourceReducer(sa) || !addManaCostFromCardData(cardData, inactiveBuilder)) {
                    val effectiveCost = computeEffectiveCost(sa, player)
                    if (effectiveCost != null && !effectiveCost.isNoCost) {
                        addManaCostFromForge(effectiveCost, inactiveBuilder)
                    } else {
                        addManaCostFromCardData(cardData, inactiveBuilder)
                    }
                }
                builder.addInactiveActions(inactiveBuilder)
                if (!preferAltCostFirst) {
                    addHandAltCostCastActions(
                        card = forgeCard,
                        player = player,
                        instanceId = instanceId,
                        grpId = grpId,
                        altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                        builder = builder,
                    )
                }
                // Adventure / Omen offers are independent of the main face's
                // payability — emit them even when the main cast is unaffordable.
                addSecondaryFaceCastActions(forgeCard, player, instanceId, grpId, cardSnap, builder)
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

            val cardData = snap.boundCards[fid]?.data
            val printedCostAdded = usesPaymentSourceReducer(sa) && addManaCostFromCardData(cardData, actionBuilder)
            val effectiveCost = if (printedCostAdded) null else computeEffectiveCost(sa, player)
            if (effectiveCost != null && !effectiveCost.isNoCost) {
                addManaCostFromForge(effectiveCost, actionBuilder)
                val autoTap =
                    buildAutoTapSolution(
                        effectiveCost,
                        player,
                        idResolver = { forgeCardId -> bridge.getOrAllocInstanceId(forgeCardId) },
                        grpIdResolver = { c -> GrpId(bridge.resolveGrpId(c, bridge.getOrAllocInstanceId(ForgeCardId(c.id)).value)) },
                        cardDataLookup = { bridge.cardRepository.findByGrpId(it.value) },
                        abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                    )
                if (autoTap != null) actionBuilder.setAutoTapSolution(autoTap)
            } else if (!printedCostAdded) {
                addManaCostFromCardData(cardData, actionBuilder)
            }
            builder.addActions(actionBuilder)

            if (!preferAltCostFirst) {
                addHandAltCostCastActions(
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                    builder = builder,
                )
            }

            addSecondaryFaceCastActions(forgeCard, player, instanceId, grpId, cardSnap, builder)
        }

        // --- Hand: non-battlefield activated abilities (Channel, Ninjutsu, etc.) ---
        // Plot is intentionally NOT here — Plot's hand SA rides the Cast-with-alt-cost
        // rail via [addHandAltCostCastActions] (mirroring Warp / Sneak).
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.hasNonManaActivatedAbilities) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            ActivatedActionEmitter.emitPlayableNonManaActivatedAbilities(
                builder = builder,
                card = forgeCard,
                player = player,
                instanceId = { bridge.getOrAllocInstanceId(fid).value },
                grpId = { cardSnap.grpId },
                cardData = { _ -> snap.boundCards[fid]?.data },
                envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                autoTapSolution = { cost -> autoTapForCost(player, cost) },
            )
        }

        // --- Zone casts (graveyard, exile, command) ---
        addZoneCastActionsFromSnap(seatId, snap, builder, bridge)

        // --- Graveyard: activated abilities (Unearth, Embalm, Eternalize) ---
        addGraveyardActivatedActionsFromSnap(seatId, snap, builder, bridge)

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
     * Zone casts (graveyard, exile, command) — picks the first castable SA per
     * card and dispatches via [CastRails]. Each (zone, rail-bucket) pair is
     * declared in [zoneRailBuckets]; rails matching the SA win, otherwise the
     * fallback path emits a printed-cost or best-effort shape. Unpayable zone
     * casts stay visible as inactive actions so automation does not repeatedly
     * submit a cast that Forge will bounce back to the source zone.
     */
    private fun addZoneCastActionsFromSnap(
        seatId: Int,
        snap: GsmSnapshot,
        builder: ActionsAvailableReq.Builder,
        bridge: GameBridge,
    ) {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return
        for ((zoneId, rails) in zoneRailBuckets) {
            val zone = snap.zones[zoneId] ?: continue
            for (fid in zone.contents) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val castable = getAllCastableAbilities(forgeCard, player)
                if (castable.isEmpty()) continue
                val sa = castable.first()
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                val cardSnap = snap.objects[fid]
                val sourceGrpId =
                    cardSnap?.grpId
                        ?: bridge.resolveGrpId(forgeCard, instanceId)
                val bound = snap.boundCards[fid]
                val rail = rails.firstOrNull { it.saPredicate(sa) }
                val canPay = canPayManaCost(sa, player)
                val omit = rail?.omitGrpIdAndFacetId == true
                val actionGrpId =
                    when (rail?.grpIdMode) {
                        ZoneCastGrpIdMode.OtherSide -> cardSnap?.othersideGrpId?.takeIf { it > 0 } ?: sourceGrpId
                        else -> sourceGrpId
                    }
                val actionFacetId =
                    when {
                        rail?.grpIdMode == ZoneCastGrpIdMode.OtherSide && cardSnap?.othersideGrpId?.takeIf { it > 0 } != null ->
                            bridge.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(fid)).value
                        else -> instanceId
                    }

                val actionBuilder =
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(instanceId)
                if (canPay) {
                    actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
                }
                if (!omit) {
                    actionBuilder.setGrpId(actionGrpId)
                    actionBuilder.setFacetId(actionFacetId)
                }
                if (rail?.emitAlternativeSourceZcid == true) {
                    actionBuilder.setAlternativeSourceZcid(instanceId)
                }

                if (rail != null) {
                    configureZoneCastRailShape(actionBuilder, sa, rail, bound, player)
                } else {
                    configureZoneCastFallback(actionBuilder, sa, bound, player)
                }
                if (canPay) {
                    builder.addActions(actionBuilder)
                } else {
                    builder.addInactiveActions(actionBuilder)
                }
            }
        }
    }

    /** Per-source-zone rail buckets for [addZoneCastActionsFromSnap]. Empty
     *  buckets (e.g. COMMAND) participate in iteration so cards there hit the
     *  fallback path — historically commander-cast shape. */
    private val zoneRailBuckets: List<Pair<Int, List<ZoneCastRail>>> =
        listOf(
            ZoneIds.EXILE to CastRails.fromExile,
            ZoneIds.P1_GRAVEYARD to CastRails.fromGraveyard,
            ZoneIds.P2_GRAVEYARD to CastRails.fromGraveyard,
            ZoneIds.COMMAND to emptyList(),
        )

    /**
     * Graveyard-zone activated abilities (Unearth, Embalm, Eternalize, …).
     *
     * Walks each own-graveyard card's `forgeCard.spellAbilities`, accepts
     * activated non-mana abilities whose `canPlay()` passes (Forge enforces
     * `ActivationZone$ Graveyard` and `SorcerySpeed$` checks). Emits
     * `Activate_add3` with the minimal envelope: `instanceId + abilityGrpId +
     * manaCost` (each mana slot echoes `abilityGrpId`). NO `grpId` /
     * `facetId` / `shouldStop` — graveyard activations omit all three.
     */
    @Suppress("CyclomaticComplexMethod") // mirrors the hand-zone activated-ability emit path; same shape, same complexity
    private fun addGraveyardActivatedActionsFromSnap(
        seatId: Int,
        snap: GsmSnapshot,
        builder: ActionsAvailableReq.Builder,
        bridge: GameBridge,
    ) {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return
        val graveyardZoneId =
            when (seatId) {
                1 -> ZoneIds.P1_GRAVEYARD
                2 -> ZoneIds.P2_GRAVEYARD
                else -> return
            }
        val zone = snap.zones[graveyardZoneId] ?: return
        for (fid in zone.contents) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.hasNonManaActivatedAbilities) continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val cardData = snap.boundCards[fid]?.data
            for (ability in getNonManaActivatedAbilities(forgeCard, player)) {
                if (!ability.canPlay()) continue
                val canPay =
                    try {
                        ComputerUtilMana.canPayManaCost(ability, player, 0, false)
                    } catch (_: Exception) {
                        false
                    }
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                val registry = bridge.abilityRegistryFor(forgeCard, cardData)
                val abilityGrpId = registry?.forSpellAbility(ability.id) ?: 0
                ActivatedActionEmitter.emitActivatedAbilityAction(
                    builder = builder,
                    instanceId = instanceId,
                    grpId = cardSnap.grpId,
                    abilityGrpId = abilityGrpId,
                    uniqueAbilityId = ActivatedActionEmitter.uniqueAbilityIdFor(cardData, abilityGrpId),
                    abilityCost = ability.payCosts?.totalMana,
                    canPay = canPay,
                    envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                )
            }
        }
    }

    private fun emitPlayLandAction(
        builder: ActionsAvailableReq.Builder,
        instanceId: Int,
        grpId: Int,
        canPlay: Boolean,
    ) {
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.Play_add3)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
        if (canPlay) {
            builder.addActions(actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)))
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    /**
     * Configure a Cast action's keyword-specific fields per the rail's row in
     * [CastRails]. Reference fixtures live under engine/src/test/resources/puzzles/
     * (plot-railway-brawler, foretell-demon-bolt, disturb-lunarch,
     * escape-glimpse-of-freedom). The rail descriptor encodes:
     *
     *  - `alternativeGrpId` source (Universal-149 vs per-card row from BoundCard)
     *  - `abilityGrpId` mode (None / FixedKeyword / EchoAlternative)
     *  - whether mana cost is emitted, and whether each ManaRequirement echoes
     *    the alternativeGrpId.
     *
     * `omitGrpIdAndFacetId` is honored by the caller before calling here.
     */
    private fun configureZoneCastRailShape(
        actionBuilder: Action.Builder,
        sa: SpellAbility,
        rail: ZoneCastRail,
        bound: BoundCard?,
        player: Player,
    ) {
        val altCosts = bound?.altCosts ?: emptyList()
        val altSource = rail.altGrpIdSource
        val needsCostAware =
            altSource is AltGrpIdSource.FromBoundCard && altSource.lookupMode == LookupMode.CostAware
        val payCostPairs: List<Pair<ManaColor, Int>> =
            if (needsCostAware) {
                computeEffectiveCost(sa, player)
                    ?.takeIf { !it.isNoCost }
                    ?.let { forgeManaCostToPairs(it) }
                    ?: emptyList()
            } else {
                emptyList()
            }
        val altGrpId = resolveAltGrpId(rail, altCosts, payCostPairs)
        if (altGrpId > 0) actionBuilder.setAlternativeGrpId(altGrpId)

        val abilityGrpId =
            when (val mode = rail.abilityGrpIdMode) {
                AbilityGrpIdMode.None -> 0
                is AbilityGrpIdMode.FixedKeyword -> mode.baseId
                AbilityGrpIdMode.EchoAlternative -> altGrpId
            }
        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)

        if (rail.emitManaCost) {
            emitAltCostManaCost(
                actionBuilder,
                sa,
                player,
                abilityGrpIdEcho = if (rail.echoAlternativeOnMana) altGrpId else 0,
            )
        }
    }

    /**
     * Fallback for zone-cast SAs that don't match any [CastRails] rail —
     * unrecognized alt-costs (Madness) and no-alt zone casts
     * (commander, etc.). Emit printed mana cost from [CardData] when there's
     * no alt cost; otherwise emit effective SA cost with optional abilityGrpId
     * lookup. Best-effort shape until a CastRails row lands for the keyword.
     */
    private fun configureZoneCastFallback(
        actionBuilder: Action.Builder,
        sa: SpellAbility,
        bound: BoundCard?,
        player: Player,
    ) {
        val altCost = sa.alternativeCost
        if (altCost == null) {
            val effectiveCost = computeEffectiveCost(sa, player)
            if (effectiveCost != null && !effectiveCost.isNoCost) {
                addManaCostFromForge(effectiveCost, actionBuilder)
            } else {
                val cardData = bound?.data
                if (cardData != null) {
                    for ((color, count) in cardData.manaCost) {
                        actionBuilder.addManaCost(ManaRequirement.newBuilder().addColor(color).setCount(count))
                    }
                }
            }
        } else {
            val keywordId = KeywordAbilityIds.fromForgeAltCostName(altCost.name)
            val abilityGrpId = if (keywordId != null) bound?.altCost(keywordId)?.abilityGrpId ?: 0 else 0
            if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
            emitAltCostManaCost(actionBuilder, sa, player, abilityGrpIdEcho = 0)
        }
    }

    /** Emit the SA's effective mana cost; echo [abilityGrpIdEcho] on each
     *  ManaRequirement when non-zero (the per-card alt-cost ability id is
     *  what the client tags every mana symbol with for keyword-cost casts). */
    private fun emitAltCostManaCost(
        actionBuilder: Action.Builder,
        sa: SpellAbility,
        player: Player,
        abilityGrpIdEcho: Int,
    ) {
        val effectiveCost = computeEffectiveCost(sa, player)
        if (effectiveCost == null || effectiveCost.isNoCost) return
        if (abilityGrpIdEcho > 0) {
            addManaCostFromForge(effectiveCost, actionBuilder, abilityGrpIdEcho)
        } else {
            addManaCostFromForge(effectiveCost, actionBuilder)
        }
    }

    /**
     * Shared action list builder — pure overload with function params.
     *
     * @param player Forge player for the seat.
     * @param seatId Arena seat identifier (for logging).
     * @param checkLegality true → live legality checks for hand/battlefield actions
     *   (canPlayLand, canPayManaCost, activated ability canPlay, autoTapSolution,
     *   inactive land actions). Zone-cast actions also check mana payability.
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
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry? = { _, _ -> null },
        cardRepository: CardRepository? = null,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()

        // Battlefield permanents: ActivateMana + Activate
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            // Naive mode only cares about ActivateMana — skip tapped cards entirely
            if (!checkLegality && card.isTapped) continue

            val instanceId = idResolver(ForgeCardId(card.id)).value
            val grpId = grpIdResolver(card).value

            // ActivateMana — untapped permanents with mana abilities
            if (!card.isTapped && card.manaAbilities.isNotEmpty()) {
                builder.addAllActions(
                    ActivatedActionEmitter.buildActivateManaAction(
                        card,
                        instanceId,
                        grpId,
                        cardDataLookup,
                        abilityRegistryLookup,
                    ),
                )
            }

            // Activate — non-mana activated abilities (only with legality checks)
            if (checkLegality) {
                val cardData = cardDataLookup(GrpId(grpId))
                ActivatedActionEmitter.emitPlayableNonManaActivatedAbilities(
                    builder = builder,
                    card = card,
                    player = player,
                    instanceId = { instanceId },
                    grpId = { grpId },
                    cardData = { _ -> cardData },
                    envelope = ActivatedActionEmitter.Envelope.PERMANENT_SOURCE,
                    abilityRegistryLookup = abilityRegistryLookup,
                    autoTapSolution = { cost ->
                        buildAutoTapSolution(
                            cost,
                            player,
                            idResolver,
                            grpIdResolver,
                            cardDataLookup,
                            abilityRegistryLookup,
                        )
                    },
                )
            }
        }

        // Hand cards: Lands + Spells
        val handCards = player.getZone(ForgeZoneType.Hand).cards

        // Lands: playable → actions, not playable → inactiveActions (legality only)
        for (card in CardLists.filter(handCards, CardPredicates.LANDS)) {
            val instanceId = idResolver(ForgeCardId(card.id)).value
            val grpId = grpIdResolver(card).value
            val canPlay =
                if (checkLegality) {
                    val landAbility = LandAbility(card, card.currentState)
                    landAbility.activatingPlayer = player
                    player.canPlayLand(card, false, landAbility)
                } else {
                    false
                }
            emitPlayLandAction(builder, instanceId, grpId, canPlay)
        }

        // Non-land spells (Cast before Activate_add3 — client uses emission order for text assignment)
        for (card in CardLists.filter(handCards, CardPredicates.NON_LANDS)) {
            val instanceId = idResolver(ForgeCardId(card.id)).value
            val grpId = grpIdResolver(card).value
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
                val altCosts =
                    cardRepository?.let { repo ->
                        BoundCard.bindAltCosts(repo.findByGrpId(grpId), repo)
                    } ?: emptyList()
                addHandAltCostCastActions(
                    card = card,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    altCosts = altCosts,
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
                ActivatedActionEmitter.emitPlayableNonManaActivatedAbilities(
                    builder = builder,
                    card = card,
                    player = player,
                    instanceId = { idResolver(ForgeCardId(card.id)).value },
                    grpId = { grpIdResolver(card).value },
                    cardData = { actionGrpId -> cardDataLookup(GrpId(actionGrpId)) },
                    envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                    abilityRegistryLookup = abilityRegistryLookup,
                    autoTapSolution = { cost ->
                        buildAutoTapSolution(
                            cost,
                            player,
                            idResolver,
                            grpIdResolver,
                            cardDataLookup,
                            abilityRegistryLookup,
                        )
                    },
                )
            }
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
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry? = { _, _ -> null },
    ): Pair<List<Action>, List<Action>> {
        val (actions, inactive) =
            buildIndexedHandCastActionsForCard(
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
        return actions.map { it.action } to inactive.map { it.action }
    }

    internal fun buildIndexedHandCastActionsForCard(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        checkLegality: Boolean,
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry? = { _, _ -> null },
    ): Pair<List<IndexedCastAction>, List<IndexedCastAction>> {
        val cardData = cardDataLookup(GrpId(grpId))
        if (!checkLegality) {
            return listOf(IndexedCastAction(0, buildFallbackCastAction(instanceId, grpId, cardData))) to emptyList()
        }

        val actions = mutableListOf<IndexedCastAction>()
        val inactive = mutableListOf<IndexedCastAction>()
        val castable = getAllCastableAbilities(card, player)
        if (castable.isEmpty()) return emptyList<IndexedCastAction>() to emptyList()

        for ((abilityIndex, sa) in castable.withIndex()) {
            if (sa.isAdventure) continue
            if (CastRails.handWithAltCost.any { it.saPredicate(sa) }) continue
            if (hasUnmetTargeting(sa) || hasNoLegalCharmModes(sa)) {
                log.debug("ActionMapper: skipping {} variant — no legal targets or modes", card.name)
                continue
            }
            val canPay = canPayManaCost(sa, player)
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
            val indexed = IndexedCastAction(abilityIndex, action)
            if (canPay) actions.add(indexed) else inactive.add(indexed)
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
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardData: CardData?,
        cardDataLookup: (GrpId) -> CardData?,
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
            val autoTap =
                buildAutoTapSolution(
                    effectiveCost,
                    player,
                    idResolver,
                    grpIdResolver,
                    cardDataLookup,
                    abilityRegistryLookup,
                )
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

    /**
     * Emit Adventure / Omen offers for a hand card. Both ride a Secondary
     * state (subtype "Adventure" or "Omen") and are independent of the main
     * face's payability. Called from both the affordable and unaffordable
     * main-cast branches so the secondary face surfaces regardless.
     *
     * Per-action-type field divergence: CastAdventure carries `grpId =
     * creature face` (the client rejects unknown grpIds on IsPrimaryCard=0
     * Adventure faces); CastOmen and CastLeftRoom/CastRightRoom omit
     * `grpId`. That asymmetry is intentional, not an oversight.
     */
    private fun addSecondaryFaceCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        cardSnap: leyline.game.snapshot.CardSnapshot,
        builder: ActionsAvailableReq.Builder,
    ) {
        if (cardSnap.isAdventureCard) {
            val advAction = buildAdventureAction(card, player, instanceId, grpId, checkLegality = true)
            if (advAction != null) {
                builder.addActions(advAction)
            } else {
                buildInactiveAdventureAction(card, player, instanceId, grpId)
                    ?.let { builder.addInactiveActions(it) }
            }
        }
        if (cardSnap.isOmenCard) {
            val omenAction = buildOmenAction(card, player, instanceId, checkLegality = true)
            if (omenAction != null) {
                builder.addActions(omenAction)
            } else {
                buildInactiveOmenAction(card, player, instanceId)
                    ?.let { builder.addInactiveActions(it) }
            }
        }
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
            val canCast = canPlayAndPayManaCost(adventureSa, player)
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

    /**
     * Emit one CastLeftRoom and/or CastRightRoom action per locked door whose
     * SA is castable. Each emit carries `actionType + instanceId + manaCost`
     * only — door identity is encoded by `actionType` alone (no grpId,
     * facetId, abilityGrpId, alternativeGrpId).
     *
     * From hand, Forge surfaces door SAs via `card.getSpells()` (the split-cast
     * shape — `cardStateName=LeftSplit/RightSplit`). From battlefield, the
     * locked door's SA comes from `card.getUnlockAbility(state)`.
     */
    private fun addRoomCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        builder: ActionsAvailableReq.Builder,
        checkLegality: Boolean,
    ) {
        for (state in card.lockedRooms) {
            val descriptor = RoomDoorCastDescriptors.forState(state) ?: continue
            val sa = descriptor.pickSpellAbility(card) ?: continue
            sa.setActivatingPlayer(player)
            val canPay =
                if (checkLegality) {
                    try {
                        sa.canPlay() && ComputerUtilMana.canPayManaCost(sa, player, 0, false)
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    true
                }
            val actionBuilder =
                Action
                    .newBuilder()
                    .setActionType(descriptor.actionType)
                    .setInstanceId(instanceId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(descriptor.actionType))
            val effective = computeEffectiveCost(sa, player)
            val manaCost = effective?.takeIf { !it.isNoCost } ?: sa.payCosts?.totalMana?.takeIf { !it.isNoCost }
            if (manaCost != null) {
                addManaCostFromForge(manaCost, actionBuilder)
            }
            if (canPay) {
                builder.addActions(actionBuilder)
            } else {
                builder.addInactiveActions(actionBuilder)
            }
        }
    }

    /**
     * Emit the `Special_TurnFaceUp_add3` action for a face-down disguise
     * permanent. The action's `alternativeGrpId` is the per-card "Turn face
     * up" ability grpId (resolved via the AbilityRegistry), and `manaCost`
     * is the printed disguise cost from the SA.
     *
     * Field divergence from regular Cast / Activate: no `grpId`, no
     * `facetId` — the action-type alone identifies the target permanent
     * (sibling pattern to CastOmen / CastLeftRoom).
     */
    private fun addSpecialTurnFaceUpActions(
        card: Card,
        player: Player,
        instanceId: Int,
        cardData: CardData?,
        fallbackAlternativeGrpId: Int,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        builder: ActionsAvailableReq.Builder,
    ) {
        val turnFaceUpSa =
            card.spellAbilities.firstOrNull { it.isDisguiseUp } ?: return
        turnFaceUpSa.setActivatingPlayer(player)
        val canPay =
            try {
                ComputerUtilMana.canPayManaCost(turnFaceUpSa, player, 0, false)
            } catch (_: Exception) {
                false
            }
        val registry = abilityRegistryLookup(card, cardData)
        val alternativeGrpId = registry?.forSpellAbility(turnFaceUpSa.id) ?: fallbackAlternativeGrpId
        if (alternativeGrpId == 0) return
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.SpecialTurnFaceUp_add3)
                .setInstanceId(instanceId)
                .setAlternativeGrpId(alternativeGrpId)
                .setAlternativeSourceZcid(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.SpecialTurnFaceUp_add3))
        val effectiveCost = computeEffectiveCost(turnFaceUpSa, player)
        val manaCost =
            effectiveCost?.takeIf { !it.isNoCost }
                ?: turnFaceUpSa.payCosts?.totalMana?.takeIf { !it.isNoCost }
        if (manaCost != null) {
            addManaCostFromForge(manaCost, actionBuilder, alternativeGrpId)
        }
        if (canPay) {
            builder.addActions(actionBuilder)
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    /**
     * Build a CastOmen action for an Omen-capable card, or null if not castable.
     * Mirrors [buildAdventureAction] but emits the minimal envelope —
     * `actionType + instanceId + manaCost` only. No grpId / facetId. The Omen
     * face is encoded by `actionType` alone (sibling: CastLeftRoom).
     */
    private fun buildOmenAction(
        card: Card,
        player: Player,
        instanceId: Int,
        checkLegality: Boolean,
    ): Action? {
        val omenState = card.getState(CardStateName.Secondary) ?: return null
        val omenSa = omenState.nonManaAbilities?.firstOrNull() ?: return null

        if (checkLegality) {
            omenSa.setActivatingPlayer(player)
            val canCast =
                try {
                    omenSa.canPlay() && ComputerUtilMana.canPayManaCost(omenSa, player, 0, false)
                } catch (_: Exception) {
                    false
                }
            if (!canCast) return null
        }

        val builder =
            Action
                .newBuilder()
                .setActionType(ActionType.CastOmen)
                .setInstanceId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastOmen))
        val effective = computeEffectiveCost(omenSa, player)
        val manaCost = effective?.takeIf { !it.isNoCost } ?: omenSa.payCosts?.totalMana?.takeIf { !it.isNoCost }
        if (manaCost != null) {
            addManaCostFromForge(manaCost, builder)
        }
        return builder.build()
    }

    /** Build an inactive CastOmen action (unaffordable), or null if card has no Omen state. */
    private fun buildInactiveOmenAction(
        card: Card,
        player: Player,
        instanceId: Int,
    ): Action? {
        val omenState = card.getState(CardStateName.Secondary) ?: return null
        val omenSa = omenState.nonManaAbilities?.firstOrNull() ?: return null
        omenSa.setActivatingPlayer(player)
        if (!omenSa.canPlay()) return null
        val builder =
            Action
                .newBuilder()
                .setActionType(ActionType.CastOmen)
                .setInstanceId(instanceId)
        val effective = computeEffectiveCost(omenSa, player)
        val manaCost = effective?.takeIf { !it.isNoCost } ?: omenSa.payCosts?.totalMana?.takeIf { !it.isNoCost }
        if (manaCost != null) {
            addManaCostFromForge(manaCost, builder)
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
     * Hand-zone alt-cost casts — emit one [Action] per eligible alt-cost SA.
     * Iterates [CastRails.handWithAltCost]; a rail matches when its
     * [HandWithAltCost.saPredicate] holds for the SA. Each action carries:
     *
     *  - `instanceId` = hand card iid, `grpId` = card grpId, `facetId` = iid
     *  - `abilityGrpId` = 0 (the alt-cost row is on `alternativeGrpId`)
     *  - `alternativeGrpId` = per-card row resolved per the rail's lookup mode
     *  - `manaCost` entries echo `alternativeGrpId` on each slot
     *
     * Madness and Flashback are intentionally NOT in
     * [CastRails.handWithAltCost] — they ride other rails (OptionalAction for
     * Madness, zone-cast for Flashback).
     */
    private fun addHandAltCostCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        altCosts: List<AltCostBinding>,
        builder: ActionsAvailableReq.Builder,
    ) {
        val castable = getAllCastableAbilities(card, player)
        val emitted = mutableSetOf<Pair<Int, List<Pair<ManaColor, Int>>>>()
        for (sa in castable) {
            val rail = CastRails.handWithAltCost.firstOrNull { it.saPredicate(sa) } ?: continue
            if (rail.kind == AltCostKind.MUTATE && hasUnmetTargeting(sa)) continue
            val canPay = canPayManaCost(sa, player)
            if (!canPay) continue

            val effectiveCost = computeEffectiveCost(sa, player)
            val payCostPairs: List<Pair<ManaColor, Int>> =
                effectiveCost?.takeIf { !it.isNoCost }?.let { forgeManaCostToPairs(it) } ?: emptyList()
            val alternativeGrpId = resolveAltGrpId(rail, altCosts, payCostPairs)
            if (alternativeGrpId <= 0) continue
            if (!emitted.add(alternativeGrpId to payCostPairs)) continue

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

    internal fun passOnlyActions(): ActionsAvailableReq =
        ActionsAvailableReq
            .newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

    @Suppress("LongParameterList")
    private fun buildAutoTapSolution(
        manaCost: ManaCost,
        player: Player,
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): AutoTapSolution? =
        ActionAutoTapSupport.build(
            manaCost,
            ActionBuildContext(player, idResolver, grpIdResolver, cardDataLookup, abilityRegistryLookup),
        )

    internal fun computeEffectiveCost(
        sa: SpellAbility,
        player: Player,
    ): forge.card.mana.ManaCost? = ActionManaCosts.computeEffectiveCost(sa, player)

    private fun usesPaymentSourceReducer(sa: SpellAbility): Boolean {
        val host = sa.hostCard ?: return false
        return host.hasKeyword(Keyword.CONVOKE) || host.hasKeyword(Keyword.IMPROVISE)
    }

    private fun addManaCostFromCardData(
        cardData: CardData?,
        actionBuilder: Action.Builder,
    ): Boolean {
        if (cardData == null || cardData.manaCost.isEmpty()) return false
        for ((color, count) in cardData.manaCost) {
            actionBuilder.addManaCost(ManaRequirement.newBuilder().addColor(color).setCount(count))
        }
        return true
    }

    internal fun forgeManaCostToPairs(manaCost: forge.card.mana.ManaCost): List<Pair<ManaColor, Int>> =
        ActionManaCosts.forgeManaCostToPairs(manaCost)

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
    ) = ActionManaCosts.addManaCostFromForge(manaCost, actionBuilder, abilityGrpId)

    internal fun forgeManaCostToRequirements(
        manaCost: forge.card.mana.ManaCost,
        abilityGrpId: Int? = null,
    ): List<ManaRequirement> = ActionManaCosts.forgeManaCostToRequirements(manaCost, abilityGrpId)

    internal fun producedToManaColor(produced: String): ManaColor? = ActionManaCosts.producedToManaColor(produced)

    /**
     * Strip an Action down to the minimal format used inside GSM embedded actions.
     *
     * GSM actions carry fewer fields than ActionsAvailableReq actions:
     * - Cast/CastAdventure: instanceId + manaCost + cast-variant identity fields
     * - Play: instanceId
     * - ActivateMana: instanceId + abilityGrpId
     * - Activate: instanceId + abilityGrpId + manaCost
     * - Pass/FloatMana: empty
     *
     * No grpId, facetId, shouldStop, or autoTapSolution.
     */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        if (action.actionType == ActionType.Cast || action.actionType == ActionType.CastAdventure) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.sourceId != 0) b.setSourceId(action.sourceId)
            if (action.alternativeGrpId != 0) b.setAlternativeGrpId(action.alternativeGrpId)
            if (action.alternativeSourceZcid != 0) b.setAlternativeSourceZcid(action.alternativeSourceZcid)
            b.addAllManaCost(action.manaCostList)
        } else if (action.actionType == ActionType.Play_add3) {
            b.setInstanceId(action.instanceId)
        } else if (action.actionType == ActionType.ActivateMana || action.actionType == ActionType.Activate_add3) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.actionType == ActionType.Activate_add3) b.addAllManaCost(action.manaCostList)
        } else if (action.actionType == ActionType.SpecialTurnFaceUp_add3) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.alternativeGrpId != 0) b.setAlternativeGrpId(action.alternativeGrpId)
            if (action.alternativeSourceZcid != 0) b.setAlternativeSourceZcid(action.alternativeSourceZcid)
            b.addAllManaCost(action.manaCostList)
        } else if (action.actionType != ActionType.Pass && action.actionType != ActionType.FloatMana) {
            b.setInstanceId(action.instanceId)
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

    private fun hasNoLegalCharmModes(sa: SpellAbility): Boolean {
        if (sa.api != ApiType.Charm) return false
        return CharmEffect.makePossibleOptions(sa).isEmpty()
    }
}
