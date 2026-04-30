package leyline.game.snapshot

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbilityStackInstance
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AbilityWordScanner
import leyline.game.data.CardRepository
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.ZoneIds
import org.jetbrains.annotations.VisibleForTesting
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Produces a [GsmSnapshot] by reading [Game] + [GameBridge]. This is the only
 * place in the pipeline (aside from [leyline.game.bundle.BundleBuilder]'s capture call)
 * that reads `forge.game.Game` directly. Each mapper migration grows the capture
 * to cover the newly-migrated stage's reads.
 *
 * Task 1: bare skeleton — matchId + empty collections.
 * Task 2: populates [GsmSnapshot.seats] for seats 1 and 2.
 * Task 4: populates [GsmSnapshot.zones] — hand/library/graveyard per seat +
 *   shared zones (battlefield/stack/exile/command).
 * Task 6: populates [GsmSnapshot.objects] — one [CardSnapshot] per card in any zone.
 *   Later tasks populate each section as the corresponding mapper migrates.
 */
object SnapshotCapture {
    private val log = org.slf4j.LoggerFactory.getLogger(SnapshotCapture::class.java)

    fun run(
        game: Game,
        bridge: GameBridge,
        matchId: String,
        gameStateId: Int,
    ): GsmSnapshot {
        val human = bridge.getPlayer(SeatId(1))
        val seats =
            listOf(1, 2).mapNotNull { seatNum ->
                val player = bridge.getPlayer(SeatId(seatNum)) ?: return@mapNotNull null
                SeatSnapshot(
                    seatId = SeatId(seatNum),
                    life = player.life,
                    startingLife = player.startingLife,
                    maxHandSize = player.maxHandSize,
                )
            }
        val zones = captureZones(game, bridge)
        val objects = captureObjects(game, bridge, zones)
        val boundCards = bindCards(objects, bridge)
        val phase = capturePhase(game, human)
        val stack = captureStack(game, bridge, human)
        val abilityWordEntries = computeAbilityWordEntries(game, bridge)
        val persistentAnnotationState =
            PersistentAnnotationState(
                activeAnnotations = bridge.annotations.snapshot(),
                nextAnnotationId = bridge.annotations.currentAnnotationId(),
                nextPersistentId = bridge.annotations.currentPersistentId(),
            )
        return GsmSnapshot.forTest(
            matchId = matchId,
            gameStateId = gameStateId,
            seats = seats,
            zones = zones,
            boundCards = boundCards,
            phase = phase,
            stack = stack,
            abilityWordEntries = abilityWordEntries,
            persistentAnnotationState = persistentAnnotationState,
            capturedAt =
                CaptureMarker(
                    gsIdBeforeCapture = -1,
                    wallClockMs = System.currentTimeMillis(),
                ),
        )
    }

    /**
     * Pair every [CardSnapshot] with its static [leyline.game.data.CardData]
     * and the alt-cost rows the card carries, resolving DB lookups once per
     * card. Phases of the BoundCard migration (S2.A in leyline-y3pf) extend
     * the bound view incrementally — each new field shifts a consumer-side
     * `bridge.cardRepository.*` call onto the bind path here.
     *
     * `data == null` for cards whose [CardSnapshot.grpId] has no DB row
     * (`EFFECT` pieces with grpId=0; tokens or unknown identities that the
     * resolver couldn't bind). [BoundCard.altCosts] is empty for those.
     */
    private fun bindCards(
        objects: Map<ForgeCardId, CardSnapshot>,
        bridge: GameBridge,
    ): Map<ForgeCardId, BoundCard> {
        val repo = bridge.cardRepository
        val out = linkedMapOf<ForgeCardId, BoundCard>()
        for ((fid, snap) in objects) {
            val data = if (snap.grpId > 0) repo.findByGrpId(snap.grpId) else null
            val altCosts = BoundCard.bindAltCosts(data, repo)
            val mobilizeCleanup = BoundCard.bindMobilizeCleanup(data, altCosts, repo)
            val parentLinkage = bindParentLinkage(snap)
            val designations =
                DesignationSet(
                    prepared = snap.preparedRole,
                    plotted = snap.plottedRole,
                    foretold = snap.isForetold,
                )
            out[fid] = BoundCard(fid, snap, data, altCosts, mobilizeCleanup, parentLinkage, designations)
        }
        return out
    }

    /**
     * Collapse the pair of parent-link instanceIds on [snap] into a single
     * [ParentLinkage] case. Returns null when the card has neither attachment
     * nor prepared-copy linkage. Prepared-copy wins when both are populated —
     * mirrors the previous direct-field precedence (the prepared-copy branch
     * ran second and overrode the attachedTo `setParentId`); the protocol
     * semantic for "prepared exile copy that's also attached" is unspecified
     * either way.
     */
    private fun bindParentLinkage(snap: CardSnapshot): ParentLinkage? {
        if (snap.preparedRole is PreparedRole.Copy) {
            snap.preparedCopySourceInstanceId?.let { return ParentLinkage.PreparedCopy(it) }
        }
        snap.attachedToInstanceId?.let { return ParentLinkage.AttachedTo(it) }
        return null
    }

    /**
     * Resolve the other face's grpId for DFC cards. Returns 0 for non-DFC.
     *
     * Scope: **transform DFCs + meld pairs only** — Forge's `Card.isDoubleFaced`
     * predicate is `isTransformable() || isMeldable()`. MDFC, Adventure, Split,
     * Flip, Saga, Battle, and Room cards do NOT enter this branch; their grpId
     * resolution goes through [GrpIdResolver]'s primary/any-face fallback chain.
     *
     * Back-face cards (Luminous Phantom, Waildrifter, etc.) have `IsPrimaryCard=0`
     * in the Arena DB, so [findGrpIdByName]'s primary-only filter misses them.
     * Fall back to [findGrpIdByNameAnyFace] which lifts that filter.
     *
     * Visible for tests that pin the back-face fallback at the projection
     * boundary. Production callers go through [bindCards].
     */
    @VisibleForTesting
    internal fun resolveOthersideGrpId(
        card: Card,
        cards: CardRepository,
    ): Int {
        if (!card.isDoubleFaced) return 0
        val otherStateName =
            if (card.currentState.stateName == forge.card.CardStateName.Backside) {
                forge.card.CardStateName.Original
            } else {
                forge.card.CardStateName.Backside
            }
        val otherState = card.getState(otherStateName) ?: return 0
        return cards.findGrpIdByName(otherState.name)
            ?: cards.findGrpIdByNameAnyFace(otherState.name)
            ?: 0
    }

    // --- Task 10: phase + stack capture ---

    /**
     * Snapshot turn/phase/priority state from [game.phaseHandler].
     * [PhaseType] is a Forge enum value; safe to hold as immutable data.
     */
    private fun capturePhase(
        game: Game,
        human: Player?,
    ): PhaseSnapshot {
        val handler = game.phaseHandler
        return PhaseSnapshot(
            turn = handler.turn.coerceAtLeast(1),
            activePlayer = SeatId(if (handler.playerTurn == human) 1 else 2),
            priorityPlayer = handler.priorityPlayer?.let { SeatId(if (it == human) 1 else 2) },
            phase = handler.phase,
        )
    }

    /**
     * Snapshot stack entries from [game.getStack()].
     *
     * Each entry captures the source card ID, owner/controller seats, and a pre-resolved
     * grpId so that [leyline.game.mapping.ZoneMapper.addStackAbilitiesFromSnapshot] never
     * needs a live Forge reference.
     */
    private fun captureStack(
        game: Game,
        bridge: GameBridge,
        human: Player?,
    ): StackSnapshot {
        val stack = game.getStack()
        if (stack.isEmpty) return StackSnapshot(emptyList())
        val entries = mutableListOf<StackEntry>()
        for (entry in stack) {
            val sourceCard = entry.sourceCard ?: continue
            val fid = ForgeCardId(sourceCard.id)
            val controller = entry.activatingPlayer
            val ownerSeat = SeatId(if (sourceCard.owner == human) 1 else 2)
            val controllerSeat = SeatId(if (controller == human) 1 else 2)
            val grpId = resolveEntryGrpId(entry, sourceCard, bridge)
            val targets = entry.targetChoices?.targetCards?.map { ForgeCardId(it.id) } ?: emptyList()
            entries.add(
                StackEntry(
                    forgeCardId = fid,
                    controller = controllerSeat,
                    owner = ownerSeat,
                    grpId = grpId,
                    targets = targets,
                ),
            )
        }
        return StackSnapshot(entries)
    }

    /**
     * Resolve grpId for a stack entry: try saga-chapter lookup first, then card name.
     * Returns 0 on failure — callers apply [GameBridge.FALLBACK_GRPID].
     */
    private fun resolveEntryGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: forge.game.card.Card,
        bridge: GameBridge,
    ): Int {
        resolveChapterGrpId(entry, sourceCard, bridge)?.let { return it }
        return bridge.cardRepository.findGrpIdByName(sourceCard.name) ?: 0
    }

    /**
     * If [entry] is a Saga chapter trigger, return the chapter-specific ability grpId.
     * Mirrors [leyline.game.mapping.ZoneMapper.resolveChapterAbilityGrpId] logic but
     * calls [leyline.game.mapping.ZoneMapper.chapterGrpIdFromCardData] directly.
     */
    private fun resolveChapterGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: forge.game.card.Card,
        bridge: GameBridge,
    ): Int? {
        if (!entry.isTrigger) return null
        val sa = entry.spellAbility ?: return null
        val trigger = sa.trigger ?: return null
        val chapterParam = trigger.getParam("Chapter") ?: return null
        val chapterIdx = chapterParam.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val sourceGrpId = bridge.cardRepository.findGrpIdByName(sourceCard.name) ?: return null
        val cardData = bridge.cardRepository.findByGrpId(sourceGrpId) ?: return null
        return leyline.game.mapping.ZoneMapper
            .chapterGrpIdFromCardData(cardData, chapterIdx)
    }

    private fun captureZones(
        game: Game,
        bridge: GameBridge,
    ): Map<Int, ZoneSnapshot> {
        val result = linkedMapOf<Int, ZoneSnapshot>()
        for (seatNum in listOf(1, 2)) {
            val player = bridge.getPlayer(SeatId(seatNum)) ?: continue
            capturePlayerZone(player, seatNum, ForgeZoneType.Hand, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Library, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Graveyard, result)
        }
        captureSharedZone(game, ForgeZoneType.Battlefield, result)
        captureSharedZone(game, ForgeZoneType.Stack, result)
        captureSharedZone(game, ForgeZoneType.Exile, result)
        captureSharedZone(game, ForgeZoneType.Command, result)
        return result
    }

    private fun capturePlayerZone(
        player: forge.game.player.Player,
        seatNum: Int,
        fz: ForgeZoneType,
        out: MutableMap<Int, ZoneSnapshot>,
    ) {
        val zone = player.getZone(fz) ?: return
        val arenaZoneId = playerZoneId(seatNum, fz) ?: return
        val arenaType = arenaTypeFor(fz)
        val visibility = visibilityFor(fz)
        out[arenaZoneId] =
            ZoneSnapshot(
                id = arenaZoneId,
                type = arenaType,
                owner = SeatId(seatNum),
                visibility = visibility,
                contents = zone.cards.map { ForgeCardId(it.id) },
            )
    }

    private fun captureSharedZone(
        game: Game,
        fz: ForgeZoneType,
        out: MutableMap<Int, ZoneSnapshot>,
    ) {
        val arenaZoneId = sharedZoneId(fz) ?: return
        val arenaType = arenaTypeFor(fz)
        out[arenaZoneId] =
            ZoneSnapshot(
                id = arenaZoneId,
                type = arenaType,
                owner = null,
                visibility = Visibility.Public,
                contents = game.getCardsIn(fz).map { ForgeCardId(it.id) },
            )
    }

    // --- Task 6: object capture ---

    /**
     * Build [CardSnapshot] for every card referenced by any captured zone.
     *
     * Cards appearing in multiple zones (shouldn't happen in practice but safe to handle)
     * are captured once — first zone wins.
     */
    private fun captureObjects(
        game: Game,
        bridge: GameBridge,
        zones: Map<Int, ZoneSnapshot>,
    ): Map<ForgeCardId, CardSnapshot> {
        val combat = game.phaseHandler?.combat
        val human = bridge.getPlayer(SeatId(1))
        // Pre-pass: walk the live battlefield once, build the prepared linkage in
        // both directions:
        //   sourceToCopy: source ForgeCardId → copy ForgeCardId
        //   copyToSource: copy ForgeCardId  → source ForgeCardId
        // Concentrates the source/copy lookup at one site so per-card snapshotting
        // doesn't re-read `Card.prepared.firstRemembered` and risk diverging from
        // this map. Stays identity-stable across Forge's `Card.id` reallocation on
        // cast (the source's `firstRemembered` always points at Forge's current
        // copy Card object, which matches whatever the Copy snapshot will see).
        val linkage = PreparedLinkage.from(game)
        val seen = linkedMapOf<ForgeCardId, CardSnapshot>()
        for (zone in zones.values) {
            for (fid in zone.contents) {
                if (fid in seen) continue
                val card = bridge.findCard(fid) ?: continue
                seen[fid] = captureCard(card, combat, bridge, human, linkage)
            }
        }
        return seen
    }

    /**
     * Read all live Forge [Card] fields and pack them into an immutable [CardSnapshot].
     *
     * This is the single point where Forge Card reads occur for the ObjectMapper path.
     * [bridge] is needed to resolve instance IDs for combat targets/blockers.
     * [human] (seat 1 player) is needed to resolve attacker target player seat IDs.
     */
    @Suppress(
        // Branches once per card-state attribute the snapshot tracks (foretold
        // face-down identity, prepared linkage, copy/token, attachment, combat,
        // designations). Each new mechanic adds one branch. Inherent.
        "CyclomaticComplexMethod",
    )
    private fun captureCard(
        card: Card,
        combat: forge.game.combat.Combat?,
        bridge: GameBridge,
        human: Player?,
        preparedLinkage: PreparedLinkage,
    ): CardSnapshot {
        val onBf = card.isInZone(ForgeZoneType.Battlefield)
        // Foretold cards are face-down — `card.type` reads from the FaceDown state
        // (Creature 2/2). Owner-perspective output must show the Original state
        // (Instant for Demon Bolt, etc.) so MTGA renders the real card and offers
        // the foretell-cast UX. (Forge's FaceDown defaults are appropriate for
        // morph / disguise on the battlefield, not for face-down-in-exile.)
        val isForetoldCard = Foretell.isForetold(card)
        val originalState =
            if (isForetoldCard) card.getOriginalState(forge.card.CardStateName.Original) else null
        val type = originalState?.type ?: card.type
        val resolvedName = originalState?.name?.takeIf { it.isNotEmpty() } ?: card.name

        // Live card types as proto CardType ordinal ints (mirrors overlayCardTypes logic)
        val liveTypeNumbers =
            type.coreTypes
                .mapNotNull { coreTypeToProto[it] }
                .sortedBy { it.number }
                .map { it.number }

        // Combat role — only for battlefield creatures
        val combatRole: CombatRole? =
            if (combat != null && onBf && type.isCreature) {
                resolveCombatRole(card, combat, bridge, human)
            } else {
                null
            }

        // Attachment — pre-resolve the parent instanceId here so ObjectMapper
        // doesn't need bridge access at projection time.
        val attachedToInstanceId =
            card.attachedTo?.let { bridge.getOrAllocInstanceId(ForgeCardId(it.id)).value }

        val ownForgeId = ForgeCardId(card.id)
        val preparedRole = resolvePreparedRole(card, onBf, ownForgeId, preparedLinkage)
        val preparedCopySourceInstanceId =
            (preparedRole as? PreparedRole.Copy)?.sourceForgeCardId?.let {
                bridge.getOrAllocInstanceId(it).value
            }

        // DFC fields — pre-resolve the other face's grpId here, so the
        // projection step ([ObjectMapper.buildFromSnapshot]) reads from
        // [CardSnapshot.othersideGrpId] without bridge access.
        val othersideGrpId = resolveOthersideGrpId(card, bridge.cardRepository)
        val currentStateNameIsBackside =
            card.currentState?.stateName == forge.card.CardStateName.Backside

        // grpId — single resolution path. [GrpIdResolver] handles every case
        // (EFFECT → 0, tokens via registry/preparedCopy/copyPermanent/standard,
        // foretold via original-state name, regular via primary/any-face).
        val instanceId = bridge.getOrAllocInstanceId(ownForgeId).value
        val grpId =
            GrpIdResolver.resolve(
                card,
                bridge.cardRepository,
                instanceId = instanceId,
                tokenRegistry = bridge.tokenRegistry,
            )

        // Owner/controller seats: seat 1 = human
        val ownerSeat = SeatId(if (card.owner == human) 1 else 2)
        val controllerSeat = SeatId(if (card.controller == human) 1 else 2)

        // Task 8: ActionMapper shape flags — read once here, not in the mapper.
        val isLand = type.isLand
        val isAdventureCard = card.isAdventureCard
        val hasManaAbilities = card.manaAbilities.isNotEmpty()
        val hasNonManaActivatedAbilities =
            card.spellAbilities.any { sa ->
                sa.isActivatedAbility && !sa.isManaAbility()
            }

        return CardSnapshot(
            forgeCardId = ForgeCardId(card.id),
            name = resolvedName,
            grpId = grpId,
            owner = ownerSeat,
            controller = controllerSeat,
            isLand = isLand,
            isAdventureCard = isAdventureCard,
            hasManaAbilities = hasManaAbilities,
            hasNonManaActivatedAbilities = hasNonManaActivatedAbilities,
            isOnBattlefield = onBf,
            // P/T captured for all creatures (legacy path sets P/T regardless of zone)
            netPower = if (type.isCreature) card.netPower else null,
            netToughness = if (type.isCreature) card.netToughness else null,
            tapped = if (onBf) card.isTapped else false,
            hasSickness = onBf && type.isCreature && card.hasSickness(),
            damage = if (onBf && type.isCreature) card.damage else 0,
            currentLoyalty = if (onBf && type.isPlaneswalker) card.currentLoyalty else 0,
            isOnAdventure = card.isOnAdventure,
            endOfTurnLeavePlay = card.isToken && card.hasSVar("EndOfTurnLeavePlay"),
            isToken = card.isToken,
            isCopyToken = card.isToken && card.copiedPermanent != null,
            attachedToInstanceId = attachedToInstanceId,
            preparedCopySourceInstanceId = preparedCopySourceInstanceId,
            liveCardTypeNumbers = liveTypeNumbers,
            isDoubleFaced = card.isDoubleFaced,
            othersideGrpId = othersideGrpId,
            currentStateNameIsBackside = currentStateNameIsBackside,
            combatRole = combatRole,
            preparedRole = preparedRole,
            plottedRole = if (Plotted.isPlotted(card)) PlottedRole.Plotted else PlottedRole.None,
            isForetold = Foretell.isForetold(card),
        )
    }

    /**
     * Resolve the [PreparedRole] for [card]. Both Source and Copy directions read
     * from the same [linkage] map, so a card never disagrees with itself across the
     * Source/Copy boundary.
     */
    private fun resolvePreparedRole(
        card: Card,
        onBattlefield: Boolean,
        ownForgeId: ForgeCardId,
        linkage: PreparedLinkage,
    ): PreparedRole =
        when {
            onBattlefield && card.isPrepared -> {
                val copy = linkage.copyOf(ownForgeId)
                if (copy == null) {
                    // `setPrepared(eff)` is the last step of the AlterAttribute Prepared
                    // path in Forge, after `eff.addRemembered(prepared)`. An observable
                    // snapshot with `isPrepared==true` should always have
                    // `firstRemembered != null`. If we hit the null branch the engine
                    // was observed mid-effect — log and degrade to None so we don't
                    // anchor a Designation pAnn on a Source we can't link.
                    log.warn(
                        "isPrepared=true with firstRemembered=null for forgeId={} ({}); skipping Source role",
                        card.id,
                        card.name,
                    )
                    PreparedRole.None
                } else {
                    PreparedRole.Source(copy)
                }
            }
            PreparedSpell.isCopy(card) ->
                PreparedRole.Copy(linkage.sourceOf(ownForgeId))
            else -> PreparedRole.None
        }

    private fun resolveCombatRole(
        card: Card,
        combat: forge.game.combat.Combat,
        bridge: GameBridge,
        human: Player?,
    ): CombatRole? {
        if (combat.isAttacking(card)) {
            val targetInstanceId: Int =
                run {
                    val defender = combat.getDefenderByAttacker(card)
                    when {
                        defender == null -> 0
                        defender is Player -> if (defender.id == human?.id) 1 else 2
                        defender is Card -> bridge.getOrAllocInstanceId(ForgeCardId(defender.id)).value
                        else -> 0
                    }
                }
            val isBlocked: Boolean? = combat.getBandOfAttacker(card)?.isBlocked()
            return CombatRole.Attacker(
                targetInstanceId = targetInstanceId,
                isBlocked = isBlocked,
            )
        }
        if (combat.isBlocking(card)) {
            val attackerIds =
                combat.getAttackersBlockedBy(card).map { atk ->
                    bridge.getOrAllocInstanceId(ForgeCardId(atk.id)).value
                }
            return CombatRole.Blocker(attackerInstanceIds = attackerIds)
        }
        return null
    }

    // Delegate to the canonical mapping in ObjectMapper — single source of truth.
    private val coreTypeToProto get() = leyline.game.mapping.ObjectMapper.coreTypeToProto

    // --- Zone ID helpers (unchanged from before) ---

    private fun playerZoneId(
        seat: Int,
        fz: ForgeZoneType,
    ): Int? =
        when (fz) {
            ForgeZoneType.Hand -> ZoneIds.handOf(seat)
            ForgeZoneType.Library -> ZoneIds.libraryOf(seat)
            ForgeZoneType.Graveyard -> ZoneIds.graveyardOf(seat)
            ForgeZoneType.Battlefield,
            ForgeZoneType.Exile,
            ForgeZoneType.Flashback,
            ForgeZoneType.Command,
            ForgeZoneType.Stack,
            ForgeZoneType.Sideboard,
            ForgeZoneType.Ante,
            ForgeZoneType.Merged,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> null
        }

    private fun sharedZoneId(fz: ForgeZoneType): Int? =
        when (fz) {
            ForgeZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ForgeZoneType.Stack -> ZoneIds.STACK
            ForgeZoneType.Exile -> ZoneIds.EXILE
            ForgeZoneType.Command -> ZoneIds.COMMAND
            ForgeZoneType.Hand,
            ForgeZoneType.Library,
            ForgeZoneType.Graveyard,
            ForgeZoneType.Flashback,
            ForgeZoneType.Sideboard,
            ForgeZoneType.Ante,
            ForgeZoneType.Merged,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> null
        }

    private fun arenaTypeFor(fz: ForgeZoneType): ZoneType =
        when (fz) {
            ForgeZoneType.Hand -> ZoneType.Hand
            ForgeZoneType.Library -> ZoneType.Library
            ForgeZoneType.Graveyard -> ZoneType.Graveyard
            ForgeZoneType.Sideboard -> ZoneType.Sideboard
            ForgeZoneType.Command -> ZoneType.Command
            ForgeZoneType.Battlefield -> ZoneType.Battlefield
            ForgeZoneType.Stack -> ZoneType.Stack
            ForgeZoneType.Exile -> ZoneType.Exile
            ForgeZoneType.Flashback,
            ForgeZoneType.Ante,
            ForgeZoneType.Merged,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> ZoneType.UNRECOGNIZED
        }

    private fun visibilityFor(fz: ForgeZoneType): Visibility =
        when (fz) {
            ForgeZoneType.Hand,
            ForgeZoneType.Library,
            ForgeZoneType.Sideboard,
            -> Visibility.Private
            ForgeZoneType.Battlefield,
            ForgeZoneType.Exile,
            ForgeZoneType.Flashback,
            ForgeZoneType.Command,
            ForgeZoneType.Stack,
            ForgeZoneType.Graveyard,
            ForgeZoneType.Ante,
            ForgeZoneType.Merged,
            ForgeZoneType.SchemeDeck,
            ForgeZoneType.PlanarDeck,
            ForgeZoneType.AttractionDeck,
            ForgeZoneType.Junkyard,
            ForgeZoneType.ContraptionDeck,
            ForgeZoneType.Subgame,
            ForgeZoneType.ExtraHand,
            ForgeZoneType.None,
            -> Visibility.Public
        }

    /**
     * Pre-run [leyline.game.annotations.AbilityWordScanner] at capture time so the diff
     * pipeline reads from snap instead of `game.registeredPlayers`.
     */
    private fun computeAbilityWordEntries(
        game: Game,
        bridge: GameBridge,
    ): List<AbilityWordScanner.AbilityWordEntry> {
        val bfCards =
            game.registeredPlayers.flatMap {
                it.getZone(ForgeZoneType.Battlefield).cards.toList()
            }
        return AbilityWordScanner.scan(
            battlefieldCards = bfCards,
            instanceIdResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
            registryResolver = { card ->
                val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                bridge.abilityRegistryFor(card, cardData)
            },
        )
    }
}
