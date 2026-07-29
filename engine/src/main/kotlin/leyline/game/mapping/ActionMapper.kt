package leyline.game.mapping

import forge.card.CardStateName
import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.chooseCastAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.ManaRequirementValue
import leyline.game.NaiveGsmAction
import leyline.game.NaiveGsmActionKind
import leyline.game.PriorityActionValue
import leyline.game.PriorityAutoTapSolutionValue
import leyline.game.PriorityCastKind
import leyline.game.PriorityPlayKind
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.LinkedFaceRole
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

    data class ActionProjection(
        val actions: ActionsAvailableReq,
        val offers: List<ActionOffer>,
    )

    /**
     * Naive action list: Cast for all non-lands, Play for all lands in hand,
     * ActivateMana for untapped permanents — no canPlay/canPay checks.
     * Client expects human's potential actions embedded during AI turn regardless of phase.
     */
    fun buildNaiveActions(
        seatId: Int,
        bridge: GameBridge,
        idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
    ): ActionsAvailableReq {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return passOnlyActions()
        return buildNaiveActionList(
            player = player,
            seatId = seatId,
            idResolver = idResolver,
            grpIdResolver = { card ->
                val iid = idResolver(ForgeCardId(card.id)).value
                GrpId(bridge.resolveGrpId(card, iid))
            },
            cardDataLookup = { grpId -> bridge.cardRepository.findByGrpId(grpId.value) },
            abilityRegistryLookup = { card, cardData -> bridge.abilityRegistryFor(card, cardData) },
            cardRepository = bridge.cardRepository,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NoNameShadowing") // action families × zone-specific shapes.
    internal fun prepareFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
        candidates: PriorityActionCandidates,
    ): PriorityActionPreparation {
        val builder = PriorityActionPreparationBuilder()

        fun addOffer(
            action: PriorityActionValue,
            command: PlayerAction,
            stackAbilityGrpId: Int? = null,
            forgeAbilityId: Int? = null,
            spellGrpId: Int? = null,
        ) {
            builder.addAction(action, command, stackAbilityGrpId, forgeAbilityId, spellGrpId)
        }

        val handZoneId = ZoneIds.handOf(seatId)
        val hand = snap.zones[handZoneId]?.contents.orEmpty()
        val battlefield = snap.zones[ZoneIds.BATTLEFIELD]?.contents.orEmpty()
        val player = bridge.getPlayer(SeatId(seatId))

        fun autoTapValueForCost(
            player: Player,
            cost: ManaCost,
        ): PriorityAutoTapSolutionValue? =
            buildAutoTapValue(
                cost,
                player,
                grpIdResolver = { card ->
                    val cardId = ForgeCardId(card.id)
                    GrpId(
                        snap.objects[cardId]?.grpId?.takeIf { it > 0 }
                            ?: snap.boundCards[cardId]?.data?.grpId
                            ?: bridge.resolveGrpId(card),
                    )
                },
                cardDataLookup = { bridge.cardRepository.findByGrpId(it.value) },
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
            )

        // --- Battlefield: ActivateMana + Activate (own permanents only) ---
        for (fid in battlefield) {
            val card = snap.objects[fid] ?: continue
            if (card.controller.value != seatId) continue

            val grpId = card.grpId

            if (!card.tapped && card.hasManaAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val boundData = snap.boundCards[fid]?.data
                for (
                manaAction in
                ActivatedActionEmitter.prepareActivateManaActions(
                    forgeCard,
                    grpId,
                    { boundData },
                    { c, d -> bridge.abilityRegistryFor(c, d) },
                    candidates.forCard(forgeCard).manaAbilities,
                )
                ) {
                    addOffer(
                        manaAction.action,
                        PlayerAction.ActivateMana(fid, manaAction.abilityIndex, ability = manaAction.ability),
                    )
                }
            } else if (card.tapped && card.hasManaAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val boundData = snap.boundCards[fid]?.data
                for (
                manaAction in
                ActivatedActionEmitter.prepareActivateManaActions(
                    forgeCard,
                    grpId,
                    { boundData },
                    { c, d -> bridge.abilityRegistryFor(c, d) },
                    candidates.forCard(forgeCard).manaAbilities,
                )
                ) {
                    addOffer(
                        manaAction.action,
                        PlayerAction.ActivateMana(fid, manaAction.abilityIndex, ability = manaAction.ability),
                    )
                }
                builder.addAllInactiveActions(
                    ActivatedActionEmitter.prepareInactiveActivateManaActions(
                        forgeCard,
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
                ActivatedActionEmitter
                    .preparePlayableNonManaActivatedAbilities(
                        card = forgeCard,
                        player = player,
                        grpId = { grpId },
                        cardData = { _ -> cardData },
                        envelope = ActivatedActionEmitter.Envelope.PERMANENT_SOURCE,
                        abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                        autoTapSolution = { cost -> autoTapValueForCost(player, cost) },
                        skipSpecialTurnFaceUp = true,
                        abilities = candidates.forCard(forgeCard).activations,
                    ).forEach { prepared ->
                        if (prepared.active) {
                            addOffer(
                                prepared.action,
                                PlayerAction.ActivateAbility(
                                    fid,
                                    prepared.abilityIndex,
                                    ability = prepared.ability,
                                ),
                                prepared.abilityGrpId.takeIf { it != 0 },
                                prepared.ability.id,
                            )
                        } else {
                            builder.addInactiveAction(prepared.action)
                        }
                    }
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
            PriorityActionRailPreparer
                .prepareRoomCasts(forgeCard, player, fid, candidates.forCard(forgeCard).casts)
                .forEach { prepared ->
                    if (prepared.active) {
                        addOffer(
                            prepared.action,
                            PlayerAction.CastSpell(fid, prepared.abilityIndex, ability = prepared.ability),
                        )
                    } else {
                        builder.addInactiveAction(prepared.action)
                    }
                }
        }

        // --- Battlefield: Special_TurnFaceUp for supported face-down creatures ---
        // A controller's supported face-down permanent surfaces a dedicated
        // Special_TurnFaceUp_add3 action carrying the per-card "Turn face up"
        // ability grpId on `alternativeGrpId` and the printed disguise cost
        // as `manaCost`. Distinct from `Activate_add3` — the client routes
        // it through a different UI flow (card-flip animation).
        for (fid in battlefield) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.controller.value != seatId) continue
            val faceDownKind = cardSnap.faceDownKind ?: continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val cardData = snap.boundCards[fid]?.data
            PriorityActionRailPreparer
                .prepareTurnFaceUp(
                    card = forgeCard,
                    player = player,
                    cardId = fid,
                    cardData = cardData,
                    fallbackAlternativeGrpId =
                        when (faceDownKind) {
                            leyline.game.snapshot.FaceDownKind.Disguise ->
                                bridge.cardRepository.findKeywordAbilityGrpId(
                                    cardSnap.grpId,
                                    leyline.game.data.KeywordAbilityIds.DISGUISE,
                                ) ?: 0
                            leyline.game.snapshot.FaceDownKind.ManifestDread ->
                                leyline.game.data.KeywordAbilityIds.MANIFEST_DREAD
                        },
                    abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                    abilities = candidates.forCard(forgeCard).activations,
                )?.let { prepared ->
                    if (prepared.active) {
                        addOffer(
                            prepared.action,
                            PlayerAction.ActivateAbility(fid, prepared.abilityIndex, ability = prepared.ability),
                            forgeAbilityId = prepared.ability.id,
                        )
                    } else {
                        builder.addInactiveAction(prepared.action)
                    }
                }
        }
        // --- Hand: lands ---
        for (fid in hand) {
            val card = snap.objects[fid] ?: continue
            if (!card.isLand) continue
            val grpId = card.grpId
            val landAbility = bridge.findCard(fid)?.let { candidates.forCard(it).landAbility }
            val canPlayLand = landAbility != null && player?.canPlayLand(landAbility.hostCard, false, landAbility) == true
            val action =
                PriorityActionValue.PlayLand(
                    PriorityPlayKind.LAND,
                    fid,
                    grpId,
                    canPlayLand && ShouldStopEvaluator.shouldStop(ActionType.Play_add3),
                )
            if (canPlayLand) {
                addOffer(action, PlayerAction.PlayLand(fid))
            } else {
                builder.addInactiveAction(action)
            }
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
                PriorityActionRailPreparer
                    .prepareRoomCasts(forgeCard, player, fid, candidates.forCard(forgeCard).casts)
                    .forEach { prepared ->
                        if (prepared.active) {
                            addOffer(
                                prepared.action,
                                PlayerAction.CastSpell(fid, prepared.abilityIndex, ability = prepared.ability),
                            )
                        } else {
                            builder.addInactiveAction(prepared.action)
                        }
                    }
                continue
            }
            val castable = candidates.forCard(forgeCard).casts
            val sa =
                castable.firstOrNull { it.hasParam("WithoutManaCost") }
                    ?: choosePrimaryHandCastAbility(forgeCard, castable)
                    ?: continue
            val abilityIndex = castable.indexOfFirst { it === sa }
            val noLegalTargets =
                PriorityActionRailPreparer.hasUnmetTargeting(sa) ||
                    PriorityActionRailPreparer.hasNoLegalCharmModes(sa)
            val canPay =
                if (PriorityActionRailPreparer.usesPaymentSourceReducer(sa)) {
                    PriorityActionRailPreparer.canPayWithPaymentSourceReducer(sa, player)
                } else {
                    PriorityActionRailPreparer.canPayManaCost(sa, player)
                }
            val grpId = cardSnap.grpId
            val preferAltCostFirst = castable.any { it.isCastFaceDown }
            val displayedManaCost =
                if (forgeCard.keywords.any { it.original.startsWith("AlternateAdditionalCost") }) {
                    snap.boundCards[fid]
                        ?.data
                        ?.manaCost
                        .orEmpty()
                        .map { (color, count) -> ManaRequirementValue(listOf(color.number), count) }
                } else {
                    CastDisplayCost.requirementValues(sa, player, snap.boundCards[fid]?.data)
                }

            if (preferAltCostFirst) {
                // The face-cast modal defaults to the first Cast offer even
                // when the visual click lands on the second card. Put Disguise's
                // face-down option first so the modal commit submits the
                // `alternativeGrpId=307` action instead of the printed spell.
                PriorityActionRailPreparer
                    .prepareHandAltCostCasts(
                        player = player,
                        cardId = fid,
                        grpId = grpId,
                        altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                        castable = castable,
                    ).forEach { (action, index, ability) ->
                        addOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                    }
            }

            if (noLegalTargets || !canPay) {
                builder.addInactiveAction(
                    PriorityActionValue.Cast(
                        kind = PriorityCastKind.CAST,
                        cardId = fid,
                        grpId = grpId,
                        manaCost = displayedManaCost,
                        shouldStop = false,
                    ),
                )
                if (!preferAltCostFirst) {
                    PriorityActionRailPreparer
                        .prepareHandAltCostCasts(
                            player = player,
                            cardId = fid,
                            grpId = grpId,
                            altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                            castable = castable,
                        ).forEach { (action, index, ability) ->
                            addOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                        }
                }
                // Adventure / Omen offers are independent of the main face's
                // payability — emit them even when the main cast is unaffordable.
                PriorityActionRailPreparer
                    .prepareSecondaryFaceCasts(forgeCard, player, fid, grpId, cardSnap, castable)
                    .forEach { prepared ->
                        val (action, index, ability) = prepared
                        if (prepared.active) {
                            addOffer(
                                action,
                                PlayerAction.CastSpell(fid, index, ability = ability),
                                spellGrpId = linkedFaceGrpId(snap.boundCards[fid], (action as PriorityActionValue.Cast).kind),
                            )
                        } else {
                            builder.addInactiveAction(action)
                        }
                    }
                continue
            }

            val action =
                CastDisplayCost.of(sa, player).let { displayCost ->
                    PriorityActionValue.Cast(
                        kind = PriorityCastKind.CAST,
                        cardId = fid,
                        grpId = grpId,
                        manaCost = displayedManaCost,
                        shouldStop = ShouldStopEvaluator.shouldStop(ActionType.Cast),
                        autoTapSolution =
                            displayCost
                                ?.takeIf { !it.isNoCost }
                                ?.let { autoTapValueForCost(player, it) },
                    )
                }
            addOffer(action, PlayerAction.CastSpell(fid, abilityIndex, ability = sa))

            if (!preferAltCostFirst) {
                PriorityActionRailPreparer
                    .prepareHandAltCostCasts(
                        player = player,
                        cardId = fid,
                        grpId = grpId,
                        altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                        castable = castable,
                    ).forEach { (action, index, ability) ->
                        addOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                    }
            }

            PriorityActionRailPreparer
                .prepareSecondaryFaceCasts(forgeCard, player, fid, grpId, cardSnap, castable)
                .forEach { prepared ->
                    val (action, index, ability) = prepared
                    if (prepared.active) {
                        addOffer(
                            action,
                            PlayerAction.CastSpell(fid, index, ability = ability),
                            spellGrpId = linkedFaceGrpId(snap.boundCards[fid], (action as PriorityActionValue.Cast).kind),
                        )
                    } else {
                        builder.addInactiveAction(action)
                    }
                }
        }

        // --- Hand: modal DFC back-face actions (spell and land faces) ---
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            PriorityActionRailPreparer
                .prepareMdfcFaces(
                    player = player,
                    cardId = fid,
                    parentGrpId = cardSnap.grpId,
                    cardRepository = bridge.cardRepository,
                    castable = candidates.forCard(forgeCard).casts,
                    mdfcLandAbility = candidates.forCard(forgeCard).mdfcLandAbility,
                ).forEach { prepared ->
                    if (prepared.active) {
                        when (val command = prepared.command) {
                            is PriorityActionRailPreparer.Command.Cast ->
                                addOffer(
                                    prepared.action,
                                    PlayerAction.CastSpell(fid, command.index, ability = command.ability),
                                )
                            PriorityActionRailPreparer.Command.PlayLand ->
                                addOffer(prepared.action, PlayerAction.PlayLand(fid))
                        }
                    } else {
                        builder.addInactiveAction(prepared.action)
                    }
                }
        }

        // --- Hand: non-battlefield activated abilities (Channel, Ninjutsu, etc.) ---
        // Plot is intentionally NOT here — Plot's hand SA rides the prepared
        // Cast-with-alt-cost rail (mirroring Warp / Sneak).
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.hasNonManaActivatedAbilities) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            ActivatedActionEmitter
                .preparePlayableNonManaActivatedAbilities(
                    card = forgeCard,
                    player = player,
                    grpId = { cardSnap.grpId },
                    cardData = { _ -> snap.boundCards[fid]?.data },
                    envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                    abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                    autoTapSolution = { cost -> autoTapValueForCost(player, cost) },
                    abilities = candidates.forCard(forgeCard).activations,
                ).forEach { prepared ->
                    if (prepared.active) {
                        addOffer(
                            prepared.action,
                            PlayerAction.ActivateAbility(fid, prepared.abilityIndex, ability = prepared.ability),
                            prepared.abilityGrpId.takeIf { it != 0 },
                            prepared.ability.id,
                        )
                    } else {
                        builder.addInactiveAction(prepared.action)
                    }
                }
        }

        // --- Zone casts (graveyard, exile, command) ---
        PriorityActionZonePreparer.prepareZoneCasts(seatId, snap, bridge, candidates).forEach { prepared ->
            if (prepared.active) {
                addOffer(
                    prepared.action,
                    checkNotNull(prepared.command),
                    prepared.stackAbilityGrpId,
                    prepared.forgeAbilityId,
                    prepared.spellGrpId,
                )
            } else {
                builder.addInactiveAction(prepared.action)
            }
        }

        // --- Graveyard: activated abilities (Unearth, Embalm, Eternalize) ---
        PriorityActionZonePreparer
            .prepareGraveyardActivations(seatId, snap, bridge, candidates)
            .forEach { prepared ->
                if (prepared.active) {
                    addOffer(
                        prepared.action,
                        checkNotNull(prepared.command),
                        prepared.stackAbilityGrpId,
                        prepared.forgeAbilityId,
                        prepared.spellGrpId,
                    )
                } else {
                    builder.addInactiveAction(prepared.action)
                }
            }

        // Pass + FloatMana always available
        addOffer(PriorityActionValue.Pass, PlayerAction.PassPriority)
        addOffer(PriorityActionValue.FloatMana, PlayerAction.PassPriority)

        val preparation = builder.build()
        val values = preparation.actions
        val manaCount = values.actions.count { it is PriorityActionValue.ActivateMana }
        val landCount = values.actions.count { it is PriorityActionValue.PlayLand }
        val castCount = values.actions.count { it is PriorityActionValue.Cast }
        val activateCount = values.actions.count { it is PriorityActionValue.Activate }
        val inactiveCount = values.inactiveActions.size
        log.debug(
            "prepareFromSnapshot: seat={} mana={} activate={} lands={} casts={} inactive={} total={}",
            seatId,
            manaCount,
            activateCount,
            landCount,
            castCount,
            inactiveCount,
            values.actions.size,
        )

        return preparation
    }

    private fun choosePrimaryHandCastAbility(
        card: Card,
        castable: List<SpellAbility>,
    ): SpellAbility? {
        if (card.keywords.none { it.original.startsWith("AlternateAdditionalCost") }) {
            return castable.firstOrNull()
        }
        return castable
            .filter { it.isSpell && it.alternativeCost == null }
            .minByOrNull { it.payCosts?.totalMana?.cmc ?: Int.MAX_VALUE }
            ?: castable.firstOrNull()
    }

    private fun emitPlayLandAction(
        builder: ActionsAvailableReq.Builder,
        instanceId: Int,
        grpId: Int,
        canPlay: Boolean,
        onActive: (Action) -> Unit = {},
    ) {
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.Play_add3)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
        if (canPlay) {
            val action = actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)).build()
            builder.addActions(action)
            onActive(action)
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    /** Builds the intentionally permissive action list embedded during the opponent's turn. */
    private fun buildNaiveActionList(
        player: Player,
        seatId: Int,
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry? = { _, _ -> null },
        cardRepository: CardRepository? = null,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()

        // Battlefield permanents: ActivateMana + Activate
        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue

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
        }

        // Hand cards: Lands + Spells
        val handCards = player.getZone(ForgeZoneType.Hand).cards

        // Lands: playable → actions, not playable → inactiveActions (legality only)
        for (card in CardLists.filter(handCards, CardPredicates.LANDS)) {
            val instanceId = idResolver(ForgeCardId(card.id)).value
            val grpId = grpIdResolver(card).value
            emitPlayLandAction(builder, instanceId, grpId, canPlay = false)
        }

        // Non-land spells (Cast before Activate_add3 — client uses emission order for text assignment)
        for (card in CardLists.filter(handCards, CardPredicates.NON_LANDS)) {
            val instanceId = idResolver(ForgeCardId(card.id)).value
            val grpId = grpIdResolver(card).value
            builder.addActions(
                buildNaiveHandCastAction(
                    card = card,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    cardDataLookup = cardDataLookup,
                ),
            )

            // CastAdventure for adventure-capable cards
            if (card.isAdventureCard) {
                val adventureSa = card.getState(CardStateName.Secondary)?.nonManaAbilities?.firstOrNull()
                val advAction = adventureSa?.let { buildNaiveAdventureAction(it, player, instanceId, grpId) }
                if (advAction != null) {
                    builder.addActions(advAction)
                }
            }
        }

        for (card in handCards) {
            addNaiveMdfcFaceActions(
                card = card,
                player = player,
                instanceId = idResolver(ForgeCardId(card.id)).value,
                parentGrpId = grpIdResolver(card).value,
                cardRepository = cardRepository,
                builder = builder,
            )
        }

        // Pass + FloatMana always available
        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        // Logging
        val manaCount = builder.actionsList.count { it.actionType == ActionType.ActivateMana }
        val landCount = builder.actionsList.count { it.actionType == ActionType.Play_add3 }
        val castCount = builder.actionsList.count { it.actionType == ActionType.Cast }
        log.debug(
            "buildNaiveActions: seat={} mana={} lands={} casts={} total={}",
            seatId,
            manaCount,
            landCount,
            castCount,
            builder.actionsCount,
        )

        return builder.build()
    }

    private fun buildNaiveHandCastAction(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (GrpId) -> CardData?,
    ): Action {
        val cardData = cardDataLookup(GrpId(grpId))
        val ability = chooseCastAbility(card, player, checkTiming = false)
        return Action
            .newBuilder()
            .setActionType(ActionType.Cast)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
            .addAllManaCost(CastDisplayCost.requirements(ability, player, cardData))
            .build()
    }

    private fun addNaiveMdfcFaceActions(
        card: Card,
        player: Player,
        instanceId: Int,
        parentGrpId: Int,
        cardRepository: CardRepository?,
        builder: ActionsAvailableReq.Builder,
    ) {
        if (!card.isModal || !card.hasState(CardStateName.Backside)) return

        val castable = getAllCastableAbilities(card, player, checkTiming = false)
        val backSpell = castable.firstOrNull(PriorityActionRailPreparer::isMdfcBackSpell)
        backSpell
            ?.let { buildMdfcSpellAction(it, player, instanceId, parentGrpId, cardRepository) }
            ?.let(builder::addActions)
    }

    private fun buildMdfcSpellAction(
        sa: SpellAbility,
        player: Player,
        instanceId: Int,
        parentGrpId: Int,
        cardRepository: CardRepository?,
    ): Action? {
        if (PriorityActionRailPreparer.hasUnmetTargeting(sa) ||
            PriorityActionRailPreparer.hasNoLegalCharmModes(sa)
        ) {
            return null
        }
        sa.setActivatingPlayer(player)
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.CastMdfc)
                .setInstanceId(instanceId)
                .setSourceId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastMdfc))
        val abilityGrpId = resolveMdfcBackAbilityGrpId(sa, parentGrpId, cardRepository)
        if (abilityGrpId != 0) {
            actionBuilder.setAbilityGrpId(abilityGrpId)
        }
        actionBuilder.addAllManaCost(CastDisplayCost.requirements(sa, player, null, abilityGrpId.takeIf { it != 0 }))
        return actionBuilder.build()
    }

    private fun resolveMdfcBackAbilityGrpId(
        sa: SpellAbility,
        parentGrpId: Int,
        cardRepository: CardRepository?,
    ): Int {
        if (cardRepository == null) return 0
        val backName = sa.cardState?.name
        val backGrpId =
            backName?.let(cardRepository::findGrpIdByNameAnyFace)
                ?: cardRepository.findLinkedFaces(parentGrpId).firstOrNull { it != parentGrpId }
                ?: return 0
        return cardRepository
            .findByGrpId(backGrpId)
            ?.abilityIds
            ?.firstOrNull()
            ?.first ?: 0
    }

    private fun linkedFaceGrpId(
        bound: BoundCard?,
        kind: PriorityCastKind,
    ): Int? =
        when (kind) {
            PriorityCastKind.ADVENTURE -> LinkedFaceRole.Adventure
            PriorityCastKind.OMEN -> LinkedFaceRole.Omen
            PriorityCastKind.CAST,
            PriorityCastKind.MDFC,
            PriorityCastKind.LEFT_ROOM,
            PriorityCastKind.RIGHT_ROOM,
            -> null
        }?.let { role -> bound?.linkedFaces?.firstOrNull { it.role == role }?.grpId }

    private fun buildNaiveAdventureAction(
        adventureSa: SpellAbility,
        player: Player,
        instanceId: Int,
        creatureGrpId: Int,
    ): Action =
        // grpId = creature face — client can't resolve IsPrimaryCard=0 adventure
        // faces and rejects the action if grpId is unknown. manaCost from the
        // adventure SA provides the correct cost for the Choose One modal.
        Action
            .newBuilder()
            .setActionType(ActionType.CastAdventure)
            .setInstanceId(instanceId)
            .setGrpId(creatureGrpId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastAdventure))
            .addAllManaCost(CastDisplayCost.requirements(adventureSa, player, null))
            .build()

    internal fun passOnlyActions(): ActionsAvailableReq =
        ActionsAvailableReq
            .newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

    @Suppress("LongParameterList")
    private fun buildAutoTapValue(
        manaCost: ManaCost,
        player: Player,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): PriorityAutoTapSolutionValue? =
        ActionAutoTapSupport.build(
            manaCost,
            ActionBuildContext(player, grpIdResolver, cardDataLookup, abilityRegistryLookup),
        )

    internal fun computeEffectiveCost(
        sa: SpellAbility,
        player: Player,
    ): forge.card.mana.ManaCost? = ActionManaCosts.computeEffectiveCost(sa, player)

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
     * - Cast/CastAdventure/CastMdfc: instanceId + manaCost + cast-variant identity fields
     * - Play/PlayMdfc: instanceId
     * - ActivateMana: instanceId + abilityGrpId
     * - Activate: instanceId + abilityGrpId + manaCost
     * - Pass/FloatMana: empty
     *
     * No grpId, facetId, shouldStop, or autoTapSolution.
     */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        if (action.actionType == ActionType.Cast ||
            action.actionType == ActionType.CastAdventure ||
            action.actionType == ActionType.CastMdfc
        ) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.sourceId != 0) b.setSourceId(action.sourceId)
            if (action.alternativeGrpId != 0) b.setAlternativeGrpId(action.alternativeGrpId)
            if (action.alternativeSourceZcid != 0) b.setAlternativeSourceZcid(action.alternativeSourceZcid)
            b.addAllManaCost(action.manaCostList)
        } else if (action.actionType == ActionType.Play_add3 || action.actionType == ActionType.PlayMdfc) {
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

    /** Compile one engine-neutral playback action into its embedded GSM form. */
    internal fun buildNaiveGsmAction(
        value: NaiveGsmAction,
        idResolver: (ForgeCardId) -> InstanceId,
    ): Action {
        val builder =
            Action
                .newBuilder()
                .setActionType(
                    when (value.kind) {
                        NaiveGsmActionKind.CAST -> ActionType.Cast
                        NaiveGsmActionKind.CAST_ADVENTURE -> ActionType.CastAdventure
                        NaiveGsmActionKind.CAST_MDFC -> ActionType.CastMdfc
                        NaiveGsmActionKind.ACTIVATE_MANA -> ActionType.ActivateMana
                        NaiveGsmActionKind.PASS -> ActionType.Pass
                        NaiveGsmActionKind.FLOAT_MANA -> ActionType.FloatMana
                    },
                )
        value.forgeCardId?.let { builder.instanceId = idResolver(it).value }
        if (value.abilityGrpId != 0) builder.abilityGrpId = value.abilityGrpId
        value.sourceForgeCardId?.let { builder.sourceId = idResolver(it).value }
        builder.addAllManaCost(value.manaCost.map(::buildManaRequirement))
        return builder.build()
    }

    private fun buildManaRequirement(value: ManaRequirementValue): ManaRequirement {
        val builder =
            ManaRequirement
                .newBuilder()
                .addAllColor(value.colors.mapNotNull(ManaColor::forNumber))
                .setCount(value.count)
        if (value.abilityGrpId != 0) builder.abilityGrpId = value.abilityGrpId
        return builder.build()
    }
}
