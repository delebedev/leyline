package leyline.game.mapping

import leyline.DevCheck
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.EffectId
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.AppliedTransfer
import leyline.game.annotations.CombatAnnotationResult
import leyline.game.annotations.CombatAnnotations
import leyline.game.annotations.MechanicAnnotations
import leyline.game.annotations.TransferAnnotations
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.annotations.ZoneTransferDetector
import leyline.game.bundle.GsmFrame
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.SnapDeltaSynthesizer
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PreparedRole
import leyline.game.state.AbilityWireIdentity
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.BridgeMutations
import leyline.game.state.CommanderDesignationKind
import leyline.game.state.CrewedThisTurnKind
import leyline.game.state.DayNightDesignationKind
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.EffectTracker
import leyline.game.state.FaceDownDisguiseKind
import leyline.game.state.FrameContext
import leyline.game.state.GameBridge
import leyline.game.state.HolderRecord
import leyline.game.state.LeftUnlockedDesignationKind
import leyline.game.state.ModifiedTypeForCrewKind
import leyline.game.state.PersistentAnnotationKind
import leyline.game.state.PersistentAnnotationStore
import leyline.game.state.PlottedDesignationKind
import leyline.game.state.PreparedDesignationKind
import leyline.game.state.QualificationKind
import leyline.game.state.RightUnlockedDesignationKind
import leyline.game.state.SaddledDesignationKind
import leyline.game.state.SaddledThisTurnKind
import leyline.game.state.TargetSpecKind
import leyline.game.state.TemporaryPermanentKind
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Orchestrates the GsmSnapshot → proto state-mapping pipeline.
 *
 * Two core methods:
 * - [buildFromSnapshot]: Full [GameStateMessage] from a captured [leyline.game.snapshot.GsmSnapshot].
 * - [buildDiff]: Diff GSM by snap-vs-snap field comparison; returns [leyline.game.state.BridgeMutations]
 *   for the caller to apply via [leyline.game.state.GameBridge.applyMutations]. Pure on
 *   ordering-sensitive outputs.
 *
 * Lifecycle GSM factories (deal-hand, mulligan, transitions) live in [leyline.game.bundle.GsmBuilder].
 * Interactive request builders (targeting, combat) live in [leyline.game.bundle.RequestBuilder].
 * Pure Forge→proto projection lives in the `mapper/` subpackage.
 *
 * ## Purity boundary
 *
 * Single contract: both [buildFromSnapshot] and [buildDiff] return
 * [leyline.game.state.BridgeMutations] as data; callers apply via [leyline.game.state.GameBridge.applyMutations].
 * No inline writes during compute, no mode flags. Ordering-sensitive writes
 * (id reallocations, limbo retires, zone recordings, persistent annotation
 * batch, nextAnnotationId) flow exclusively through the returned mutations.
 *
 * Inputs to [buildDiff] are pure values: `prev: GsmSnapshot?`, `cur: GsmSnapshot`,
 * `events: FrameEventLog`. Outputs are pure: `GameStateMessage` + [leyline.game.state.BridgeMutations].
 *
 * The acceptance forcing function for this boundary is [PureDiffReplayTest],
 * which replays recorded `(snap, events, diff)` tuples through [buildDiff] on
 * a fresh bridge and asserts byte-equal Diff GSMs across scenarios. A
 * regression there signals newly-introduced impurity.
 *
 * ## Residual in-stage bridge reads/writes (accepted, by design)
 *
 * These remain inside the pipeline for bounded reasons — not ordering-sensitive
 * for the replayed scenarios, or part of a deliberate boundary. This list is
 * a working catalog, not a completeness claim: the replay test is the real
 * contract, not the enumeration. Extend the test scenarios (targeted spells,
 * vehicles, reveals, steals) to grow the coverage before relying on the list.
 *
 * Reads of effectively-immutable / card-DB state:
 * - [leyline.game.state.GameBridge.getOrAllocInstanceId] for NEW fids (first-seen cards). Monotonic
 *   allocator; ordering-irrelevant for correctness.
 * - `bridge.cardRepository.findGrpIdByName` / `findByGrpId`. Read-only card DB.
 *
 * Reads of live Forge state (deliberate bridge boundary):
 * - `bridge.snapshotBoosts()` / `bridge.snapshotKeywords()` — capture layered-
 *   effect snapshots for diff computation. Read-only at call site.
 * - `bridge.promptBridge(seat).journal.activeReveal()` — prompt-journal read
 *   for active-reveal detection. Journal state is still bridge-attached.
 *
 * Reads-then-writes on bridge-attached tracker state (not yet lifted onto snap):
 * - `bridge.effects` (EffectTracker) — layered-effect lifecycle state.
 * - `bridge.revealProxies` — RevealedCard proxy tracker, tied to transactional
 *   reveal-choose effects that span bundles.
 * - `bridge.annotations.activeStealForgeCardIds()` / `addSteals` / `removeSteals` —
 *   steal lifecycle.
 * - `bridge.snapshotCrewState()` / `bridge.getOrAllocCrewEffectId()` /
 *   `bridge.releaseCrewEffects()` — vehicle crew lifecycle.
 * - `bridge.drainPendingTargetSpecs()` — pending targeted-spell spec drain;
 *   ordering-sensitive but currently not exercised by the replay test.
 *   Highest-priority candidate to either lift or cover.
 *
 * Incidental in-stage writes:
 * - `bridge.evictAbilityRegistry(...)` — cache invalidation for transformed
 *   cards. Side-effectful but idempotent; ordering-irrelevant.
 * - `bridge.ids.reserveNextInstanceId()` inside zone-transfer compute —
 *   reserves a counter slot without committing map writes. Monotonic, so
 *   replay on a fresh bridge starts from 1 and stays deterministic.
 *
 * Any NEW in-stage bridge touch should be justified in PR review — either
 * it joins the catalog with a scope rationale, the replay test is extended
 * to cover it, or it gets lifted onto snap.
 */
@Suppress("LargeClass") // pipeline orchestrator; stages already delegated to mapper/* and helper objects
object StateMapper {
    private val log = LoggerFactory.getLogger(StateMapper::class.java)
    private val disturbBackPlayerZoneIds =
        setOf(
            ZoneIds.P1_HAND,
            ZoneIds.P2_HAND,
            ZoneIds.P1_GRAVEYARD,
            ZoneIds.P2_GRAVEYARD,
        )

    /** Result of [buildFromSnapshot] / [buildDiff] — GSM plus metadata for message framing. */
    data class BuildResult(
        val gsm: GameStateMessage,
        /** True if a CastSpell zone transfer was detected (triggers QueuedGSM split). */
        val hasCastSpell: Boolean = false,
        /** Ordering-sensitive bridge mutations computed during the build. Caller applies via [leyline.game.state.GameBridge.applyMutations]. */
        val mutations: BridgeMutations = BridgeMutations.Companion.EMPTY,
    )

    /**
     * Build a Full [GameStateMessage] from an immutable [leyline.game.snapshot.GsmSnapshot].
     * Maps cards to client instanceIds via the bridge's card ID mapping.
     *
     * [viewingSeatId] controls hand visibility: opponent's hand cards get
     * objectInstanceIds (for card count) but no GameObjectInfo (renders face-down).
     * Use 0 to include all objects (internal snapshots for diffing).
     */
    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        /**
         * Bundle events consumed by the annotation pipeline. Defaults to closing
         * the bridge frame via [GameBridge.closeBundleFrame] — previously this was
         * done inside this function. Callers in the bundle loop (BundleBuilder)
         * pass an explicit log so the frame closes once per bundle and the
         * mapper is pure on event inputs.
         */
        events: FrameEventLog = bridge.closeBundleFrame(viewingSeatId),
    ): BuildResult {
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))
        val frame = GsmFrame.Companion.from(snap)

        // ═══ GATHER: snapshot mutable state (events arrive from caller) ═══
        // applyRevealProxies may append RevealProxiesDeleted on reveal end; keep local mutable copy.
        // Snap delta drives PowerToughnessChanged + CardTransformed instead of a parallel
        // diff in GameEventCollector — see SnapDeltaSynthesizer for the gating rules.
        val eventsMutable = events.events.toMutableList()
        if (prev != null) {
            eventsMutable += SnapDeltaSynthesizer.synthesize(prev, snap)
        }
        // Evict stale AbilityRegistry entries for transformed cards so the next
        // abilityRegistryFor() call rebuilds from the current face.
        for (ev in eventsMutable) {
            if (ev is GameEvent.CardTransformed) bridge.evictAbilityRegistry(ev.cardId.value)
        }
        val initEffectDiff = bridge.effects.emitInitEffectsOnce()
        val boostSnapshot = bridge.snapshotBoosts()
        val effectDiff = bridge.effects.diffBoosts(boostSnapshot)
        val keywordSnapshot = bridge.snapshotKeywords()
        val keywordDiff = bridge.effects.diffKeywords(keywordSnapshot)
        // Persistent annotation baseline is carried on the snapshot (captured
        // at snap time in SnapshotCapture). computeBatch is pure over this value.
        // See PersistentAnnotationStore class KDoc for lifecycle and ordering invariants.
        val persistentState = snap.persistentAnnotationState
        val persistSnapshot = persistentState.activeAnnotations
        val startPersistentId = persistentState.nextPersistentId
        val startAnnotationId = persistentState.nextAnnotationId

        // ═══ MAP: engine state → proto objects ═══
        val isBrawl = bridge.isBrawlOrCommander
        val gameVariant = if (isBrawl) GameVariant.Brawl else GameVariant.Normal

        val gameInfo =
            GameInfo
                .newBuilder()
                .setMatchID(matchId)
                .setGameNumber(1)
                .setStage(GameStage.Play_a920)
                .setType(GameType.Duel)
                .setVariant(gameVariant)
                .setMatchState(MatchState.GameInProgress)
                .setMatchWinCondition(MatchWinCondition.SingleElimination)
                .setMulliganType(MulliganType.London)
        if (isBrawl) {
            gameInfo.setDeckConstraintInfo(
                DeckConstraintInfo
                    .newBuilder()
                    .setMinDeckSize(58)
                    .setMaxDeckSize(59)
                    .setMaxSideboardSize(1)
                    .setMinCommanderSize(1)
                    .setMaxCommanderSize(2),
            )
            gameInfo.setFreeMulliganCount(1)
        }

        val player1 = PlayerMapper.buildFromSnapshot(snap, 1)
        val player2 = PlayerMapper.buildFromSnapshot(snap, 2)

        val team1 =
            TeamInfo
                .newBuilder()
                .setId(1)
                .addPlayerIds(1)
                .setStatus(TeamStatus.InGame_a458)
        val team2 =
            TeamInfo
                .newBuilder()
                .setId(2)
                .addPlayerIds(2)
                .setStatus(TeamStatus.InGame_a458)

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Standard zone layout (17 zones, IDs 18-38) — must send all for Full state
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.EXILE, ZoneType.Exile, 0, Visibility.Public))
        // Limbo zone: include all previously accumulated retired instanceIds.
        // TriggerHolder iids are spliced in after the holder-tracker diff runs
        // below — see the splice that wraps `transferResultWithHolders`. They
        // must reflect post-diff state (holder added → in zone, holder removed
        // → out of zone) so the deletion GSM's Limbo listing doesn't disagree
        // with `diffDeletedInstanceIds`.
        val limboZone =
            ZoneInfo
                .newBuilder()
                .setZoneId(ZoneIds.LIMBO)
                .setType(ZoneType.Limbo)
                .setVisibility(Visibility.Public)
        for (id in bridge.getLimboInstanceIds()) {
            limboZone.addObjectInstanceIds(id.value)
        }
        zones.add(limboZone.build())

        // Detect active reveal-choose effect, clearing stale state if engine skipped the choice.
        val activeReveal = detectActiveReveal(bridge)
        val revealedHandSeat = activeReveal?.ownerSeatId?.value

        // Player 1 zones
        if (human != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(1),
                snap,
                bridge,
                zones,
                gameObjects,
                ZoneIds.P1_HAND,
                ZoneIds.P1_LIBRARY,
                ZoneIds.P1_GRAVEYARD,
                viewingSeatId,
                revealForSeat,
                revealHand = revealedHandSeat == 1,
            )
        }
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P1_SIDEBOARD, ZoneType.Sideboard, 1))

        // Player 2 zones
        if (ai != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(2),
                snap,
                bridge,
                zones,
                gameObjects,
                ZoneIds.P2_HAND,
                ZoneIds.P2_LIBRARY,
                ZoneIds.P2_GRAVEYARD,
                viewingSeatId,
                revealForSeat,
                revealHand = revealedHandSeat == 2,
            )
        }
        zones.add(ZoneMapper.makePrivateZone(ZoneIds.P2_SIDEBOARD, ZoneType.Sideboard, 2))

        // Populate shared zones with cards.
        ZoneMapper.addSharedZoneCardsFromSnapshot(
            snap,
            ForgeZoneType.Battlefield,
            ZoneIds.BATTLEFIELD,
            bridge,
            zones,
            gameObjects,
            human,
            keywordSnapshot,
        )
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Stack, ZoneIds.STACK, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Exile, ZoneIds.EXILE, bridge, zones, gameObjects, human)
        ZoneMapper.addSharedZoneCardsFromSnapshot(snap, ForgeZoneType.Command, ZoneIds.COMMAND, bridge, zones, gameObjects, human)

        // Stack abilities (triggers, activated abilities not represented as zone cards)
        ZoneMapper.addStackAbilitiesFromSnapshot(snap, bridge, zones, gameObjects)

        // RevealedCard proxy synthesis / cleanup (may append RevealProxiesDeleted to eventsMutable)
        applyRevealProxies(activeReveal, snap, bridge, zones, gameObjects, eventsMutable)

        log.debug(
            "buildFromSnapshot: phase={} turn={} hand={} objects={} zones={}",
            snap.phase.phase,
            snap.phase.turn,
            human?.getZone(ForgeZoneType.Hand)?.size() ?: 0,
            gameObjects.size,
            zones.size,
        )

        // ═══ COMPUTE: annotation pipeline (stages 1-5) ═══
        val transferResult = ZoneTransferDetector.detectZoneTransfers(gameObjects, zones, bridge, eventsMutable)
        // Frame-scoped id resolver — uses the planned-realloc map so any consumer
        // asking "what iid will the client see for this card?" gets the
        // post-realloc answer even before applyMutations runs.
        val frameIds = FrameIdResolver(bridge, FrameIdResolver.postReallocIids(transferResult))
        val transferResultWithDecayedAffectors = transferResult.withDecayedCleanupAffectors(eventsMutable, snap, bridge, frameIds)
        val actingSeat = snap.phase.priorityPlayer?.value ?: 2
        val (annotations, transferPersistent, combatResult) =
            computeAnnotations(
                eventsMutable,
                transferResultWithDecayedAffectors,
                actingSeat,
                bridge,
                prev = prev,
                snap = snap,
                frameIds = frameIds,
            )

        val decayedCleanupSourcesThisGsm = updateDecayedCleanupSources(eventsMutable, snap, bridge, transferResultWithDecayedAffectors)

        // Snap-derived pAnn inputs — computed here where snap is in scope.
        val qualificationPersistentFromSnap =
            snap.objects.values
                .filter { it.isOnAdventure }
                .map { AnnotationBuilder.qualification(instanceId = bridge.getOrAllocInstanceId(it.forgeCardId)) }
        val eotTokens = snap.objects.values.filter { it.isOnBattlefield && it.endOfTurnLeavePlay }
        // Group EOT-sacrifice tokens by their source card so each delayed-trigger
        // registration gets its own TriggerHolder iid. The canonical shape mints
        // a transient gameObject in Limbo (grpId=5) per `registerDelayedTrigger`
        // call — keying the holder forge id on
        // `<source card forge id> + DELAYED_TRIGGER_HOLDER_FORGE_OFFSET` produces
        // a stable iid that both DelayedTriggerAffectees and per-token
        // TemporaryPermanent reference, mirroring the shared-holder shape
        // (Mobilize 3: one holder, three tokens; two Mobilize sources: two holders).
        // Tokens whose source can't be resolved (non-Mobilize EOT copy tokens, etc.)
        // fall back to a per-controller holder.
        val tokenSources: Map<CardSnapshot, ForgeCardId?> =
            eotTokens.associateWith { tokenSourceForgeId(it.forgeCardId, bridge) }
        val decayedCleanupHolders =
            decayedCleanupHoldersFromSnap(
                decayedCleanupSourcesThisGsm,
                snap,
                bridge,
                frameIds,
                transferResultWithDecayedAffectors,
            )
        val temporaryPermanentPersistentFromSnap =
            eotTokens.map { token ->
                val tokenIid = bridge.getOrAllocInstanceId(token.forgeCardId)
                val sourceForgeId = tokenSources[token]
                val mobilizeCleanup =
                    sourceForgeId?.let { source ->
                        mobilizeCleanupGrpIdForSource(source, snap)?.let { cleanupGrpId -> source to cleanupGrpId }
                    }
                // Holder iid is the per-trigger affector for Mobilize (canonical
                // shape). For generic EOT-sacrifice copies (Electroduplicate etc.)
                // legacy callers pass affectorId = tokenIid; preserve that until
                // the wider holder-pattern survey of non-Mobilize delayed triggers
                // is done.
                val affectorIid =
                    if (mobilizeCleanup != null) {
                        holderInstanceIdFor(mobilizeCleanup.first, bridge)
                    } else {
                        tokenIid
                    }
                AnnotationBuilder.temporaryPermanent(
                    tokenInstanceId = tokenIid,
                    abilityGrpId = mobilizeCleanup?.second?.let { GrpId(it) } ?: AnnotationConstants.EOT_SACRIFICE_GRP_ID,
                    affectorId = affectorIid,
                )
            } +
                decayedCleanupHolders.map { holder ->
                    AnnotationBuilder.temporaryPermanent(
                        tokenInstanceId = InstanceId(holder.parentIid),
                        abilityGrpId = GrpId(holder.cleanupGrpId),
                        affectorId = InstanceId(holder.iid),
                    )
                }
        // DelayedTriggerAffectees is only emitted when we can resolve a real
        // delayed-trigger keyword on the source (currently Mobilize). Generic
        // EOT-sacrifice copies skip this annotation — legacy behavior, until
        // the wider survey of non-Mobilize delayed-trigger sources lands.
        // Side product: the set of TriggerHolder gameObjects that must surface
        // in Limbo so the client can render the side-panel timed-effect
        // indicator. The holder's `objectSourceGrpId` carries the keyword
        // ability id (e.g. 188696 for Mobilize 3) — that's what drives the
        // icon and tooltip text; `parentId` points at the source card.
        // Build the live set of holders that should be alive this GSM (one per
        // (source card, controller) group with a Mobilize keyword on the
        // source). Real wire shape: emit the gameObject once on first
        // appearance, retire via diffDeletedInstanceIds when it disappears.
        // Lifecycle owned by [bridge.delayedTriggerHolders] — see its KDoc.
        val currentHolders = mutableListOf<HolderRecord>()
        currentHolders.addAll(decayedCleanupHolders)
        val delayedTriggerAffecteesFromSnap =
            eotTokens
                .groupBy { tokenSources[it] to it.controller.value }
                .filterValues { it.isNotEmpty() }
                .mapNotNull { (key, tokens) ->
                    val (rawSourceForgeId, seat) = key
                    val sourceForgeId = rawSourceForgeId ?: return@mapNotNull null
                    val cleanupGrpId =
                        mobilizeCleanupGrpIdForSource(sourceForgeId, snap) ?: return@mapNotNull null
                    val tokenIds = tokens.map { bridge.getOrAllocInstanceId(it.forgeCardId) }
                    val holderIid = holderInstanceIdFor(sourceForgeId, bridge)
                    val keywordGrpId = mobilizeKeywordGrpIdForSource(sourceForgeId, snap) ?: 0
                    val sourceIid = bridge.getOrAllocInstanceId(sourceForgeId).value
                    currentHolders.add(
                        HolderRecord(
                            iid = holderIid.value,
                            ownerSeat = seat,
                            objectSourceGrpId = keywordGrpId,
                            parentIid = sourceIid,
                            cleanupGrpId = cleanupGrpId,
                        ),
                    )
                    AnnotationBuilder.delayedTriggerAffectees(
                        triggerHolderId = holderIid,
                        tokenInstanceIds = tokenIds,
                        abilityGrpId = GrpId(cleanupGrpId),
                    )
                }
        // Diff against the bridge-side tracker. The client keeps cached holders
        // across GSMs by instanceId, so we only emit a gameObject for newly-added
        // holders; removed holders flow through
        // [bridge.delayedTriggerHolders.drainDeletions] into
        // diffDeletedInstanceIds in [buildDiff]. The Limbo zone listing,
        // however, must reflect the **post-diff** active set every GSM —
        // otherwise the deletion GSM ships the iid both in Limbo and in
        // diffDeletedInstanceIds, and a between-emit-and-delete GSM that
        // rebuilt Limbo without the holder would orphan the cached object.
        // assembleGsm reads zones/objects from `transferResult.patchedZones`/
        // `patchedObjects` (see assembleGsm wiring below), not the local lists,
        // so we splice both the gameObject and the Limbo membership into a
        // copy of TransferResult here.
        val holderBatch = bridge.delayedTriggerHolders.computeBatch(currentHolders)
        val postDiffActiveIids =
            (bridge.delayedTriggerHolders.activeIids() + holderBatch.added.map { it.iid }) - holderBatch.removed.toSet()
        val transferResultWithHolders =
            if (holderBatch.added.isEmpty() && holderBatch.removed.isEmpty() && postDiffActiveIids.isEmpty()) {
                transferResultWithDecayedAffectors
            } else {
                val patchedZones = transferResult.patchedZones.toMutableList()
                val patchedObjects = transferResult.patchedObjects.toMutableList()
                val existingLimbo = patchedZones.find { it.zoneId == ZoneIds.LIMBO }
                val limboBuilder =
                    (existingLimbo?.toBuilder() ?: ZoneInfo.newBuilder().setZoneId(ZoneIds.LIMBO).setType(ZoneType.Limbo))
                if (existingLimbo != null) patchedZones.removeIf { it.zoneId == ZoneIds.LIMBO }
                val baseIids = limboBuilder.objectInstanceIdsList.toMutableSet()
                // Drop deleted holders from the listing (deletion GSM).
                baseIids.removeAll(holderBatch.removed.toSet())
                // Add post-diff active holders.
                baseIids.addAll(postDiffActiveIids)
                limboBuilder.clearObjectInstanceIds()
                for (iid in baseIids) limboBuilder.addObjectInstanceIds(iid)
                for (holder in holderBatch.added) {
                    patchedObjects.add(
                        ObjectMapper.buildTriggerHolderObject(
                            instanceId = holder.iid,
                            ownerSeatId = holder.ownerSeat,
                            objectSourceGrpId = holder.objectSourceGrpId,
                            parentInstanceId = holder.parentIid,
                            uniqueAbilityGrpId = holder.cleanupGrpId,
                            uniqueAbilityId = bridge.effects.nextEffectId(),
                        ),
                    )
                }
                patchedZones.add(limboBuilder.build())
                transferResultWithDecayedAffectors.copy(patchedZones = patchedZones, patchedObjects = patchedObjects)
            }
        bridge.delayedTriggerHolders.apply(holderBatch)
        val abilityWordPersistentFromSnap =
            snap.abilityWordEntries.map { entry ->
                AnnotationBuilder.abilityWordActive(
                    instanceId = InstanceId(entry.instanceId),
                    abilityWordName = entry.abilityWordName,
                    value = entry.value,
                    threshold = entry.threshold,
                    abilityGrpId = entry.abilityGrpId?.let { GrpId(it) },
                    affectorId = InstanceId(entry.affectorId ?: entry.instanceId),
                    affectedIds = entry.affectedIds.ifEmpty { listOf(entry.instanceId) }.map { InstanceId(it) },
                )
            }
        val trainingAbilityWordPersistentFromEvents =
            trainingAbilityWordPersistentFromEvents(eventsMutable, snap, prev, bridge, frameIds)
        val preparedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    // PreparedRole.Source is set only on battlefield permanents with a
                    // live linked copy — exactly the set of cards that should carry the
                    // persistent Designation pAnn. The role-based shape avoids the stale
                    // isPrepared flags Forge keeps on retired stack/limbo card states.
                    val source = bound.designations.prepared as? PreparedRole.Source ?: return@mapNotNull null
                    AnnotationBuilder.preparedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                        preparedCopyInstanceId = bridge.getOrAllocInstanceId(source.copyForgeCardId),
                    )
                }

        // Plotted: persistent Designation (DesignationType=18) for every card with
        // PlottedRole.Plotted. The snapshot pass filtered the role to `isPlotted &&
        // isInZone(Exile)`, so no zone guard needed here.
        val plottedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isPlotted) return@mapNotNull null
                    AnnotationBuilder.plottedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }

        val saddledDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isSaddled) return@mapNotNull null
                    AnnotationBuilder.saddledDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }

        val commanderDesignationPersistentFromSnap =
            snap.boundCards.values
                .filter { it.designations.isCommander && it.snapshot.grpId > 0 }
                .flatMap { bound ->
                    val iid = frameIds.cardIid(bound.forgeCardId)
                    val grpId = GrpId(bound.snapshot.grpId)
                    val colorIdentity = bound.designations.commanderColorIdentity
                    val tax = bound.designations.commanderTax
                    listOf(
                        AnnotationBuilder.commanderPlayerDesignation(
                            seatId = bound.snapshot.owner,
                            grpId = grpId,
                            colorIdentity = colorIdentity,
                            costIncrease = tax,
                        ),
                        AnnotationBuilder.commanderObjectDesignation(
                            instanceId = iid,
                            grpId = grpId,
                            colorIdentity = colorIdentity,
                            costIncrease = tax,
                        ),
                    )
                }

        // LeftUnlocked / RightUnlocked: persistent Designation (DesignationType=19/20)
        // for every battlefield Room card with the matching door open. Snapshot
        // pass filtered to `onBf && card.isRoom`, so no zone guard needed here.
        val leftUnlockedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isLeftDoorUnlocked) return@mapNotNull null
                    AnnotationBuilder.leftUnlockedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }
        val rightUnlockedDesignationPersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.designations.isRightDoorUnlocked) return@mapNotNull null
                    AnnotationBuilder.rightUnlockedDesignation(
                        instanceId = bridge.getOrAllocInstanceId(bound.forgeCardId),
                    )
                }

        // FaceDown (REASON=Disguise=6, abilityGrpId=DISGUISE=307): persistent
        // annotation per face-down disguise creature on the battlefield. The
        // snapshot pass filtered to `isFaceDown && (Battlefield || Stack) &&
        // hasDisguiseKeyword`. Lives across the card's entire face-down
        // residence; the upsert pipeline replaces the row when the card flips
        // face-up (filter no longer matches -> row pruned via pruneStale).
        val faceDownDisguisePersistentFromSnap =
            snap.boundCards.values
                .mapNotNull { bound ->
                    if (!bound.snapshot.isFaceDownDisguise) return@mapNotNull null
                    AnnotationBuilder.faceDownPersistent(
                        instanceId = frameIds.cardIid(bound.forgeCardId),
                        reason = AnnotationConstants.FACEDOWN_REASON_DISGUISE,
                        abilityGrpId = leyline.bridge.types.GrpId(KeywordAbilityIds.DISGUISE),
                    )
                }

        // Day/Night: persistent Designation (DesignationType=10/11) on the game
        // itself, carrying the active player's running spell-count tally. Emits
        // every GSM once the state is established. Pre-first-transition is
        // wire-silent (snap.dayTime == null).
        val dayNightDesignationPersistentFromSnap: List<AnnotationInfo> =
            snap.dayTime?.let { isNight ->
                listOf(
                    AnnotationBuilder.dayNightDesignation(
                        designationType =
                            if (isNight) {
                                AnnotationConstants.DESIGNATION_TYPE_NIGHT
                            } else {
                                AnnotationConstants.DESIGNATION_TYPE_DAY
                            },
                        activePlayerSpellCount = snap.activePlayerSpellsCastThisTurn,
                    ),
                )
            } ?: emptyList()
        // Transient gain/lose Designation annotations — diff prev vs cur on the
        // `Source on battlefield with isPrepared` set. Gains insert before the
        // Stack→Battlefield Resolve ZoneTransfer for the same source iid to match
        // the protocol's bracket order (annotation 848 before 849 in the spec).
        // Loses append at the end (cast acceptance has no co-located ZT for the
        // source — the ZT is on the copy moving Exile→Stack). Skipped on full
        // snapshot rebuild (prev == null) — the persistent Designation pAnn alone
        // re-syncs client state on rebuild.
        if (prev != null) {
            insertStateDesignationTransients(annotations, prev, snap) { fid ->
                bridge.getOrAllocInstanceId(fid)
            }
            insertDayNightDesignationTransients(annotations, prev.dayTime, snap.dayTime)
        }

        // Stages 4-5 + persistent computation
        val battlefieldIids: Set<Int> = frameIds.battlefieldInstanceIds(snap)
        // Stack contents (cards) plus stack-resident Ability gameObjects — both
        // can be the affector of a TriggeringObject. The Ability instance ids
        // are synthesised against [FrameIdResolver.stackAbilityForgeId] and
        // don't appear in the snapshot's zone contents.
        val stackIids: Set<Int> = frameIds.stackInstanceIds(snap)
        val controllerOf: Map<Int, SeatId> =
            snap.boundCards.values.associate { bound ->
                bridge.getOrAllocInstanceId(bound.forgeCardId).value to bound.snapshot.controller
            }
        val frameContext =
            FrameContext(
                phase = snap.phase.phase,
                activePlayerSeat = snap.phase.activePlayer,
                battlefieldIids = battlefieldIids,
                controllerOf = controllerOf,
                stackIids = stackIids,
            )
        val remaining =
            computeRemainingAnnotations(
                eventsMutable,
                annotations,
                transferPersistent,
                initEffectDiff,
                effectDiff,
                persistSnapshot,
                startPersistentId,
                startAnnotationId,
                bridge,
                frameContext,
                frameIds,
                keywordDiff,
                combatResult,
                qualificationPersistentFromSnap = qualificationPersistentFromSnap,
                temporaryPermanentPersistentFromSnap = temporaryPermanentPersistentFromSnap,
                delayedTriggerAffecteesPersistentFromSnap = delayedTriggerAffecteesFromSnap,
                abilityWordPersistentFromSnap = abilityWordPersistentFromSnap + trainingAbilityWordPersistentFromEvents,
                preparedDesignationPersistentFromSnap = preparedDesignationPersistentFromSnap,
                plottedDesignationPersistentFromSnap = plottedDesignationPersistentFromSnap,
                saddledDesignationPersistentFromSnap = saddledDesignationPersistentFromSnap,
                commanderDesignationPersistentFromSnap = commanderDesignationPersistentFromSnap,
                leftUnlockedDesignationPersistentFromSnap = leftUnlockedDesignationPersistentFromSnap,
                rightUnlockedDesignationPersistentFromSnap = rightUnlockedDesignationPersistentFromSnap,
                dayNightDesignationPersistentFromSnap = dayNightDesignationPersistentFromSnap,
                faceDownDisguisePersistentFromSnap = faceDownDisguisePersistentFromSnap,
            )

        // ═══ ASSEMBLE: build the GSM proto ═══
        val built =
            assembleGsm(
                gameStateId,
                gameInfo.build(),
                frame,
                transferResultWithHolders,
                remaining,
                combatResult,
                team1.build(),
                team2.build(),
                player1,
                player2,
                updateType,
                actions,
                actingSeat,
                prev?.gameStateId,
            )

        // ═══ COLLECT mutations (always) ═══
        val mutations =
            BridgeMutations(
                idReallocations = transferResult.idReallocations,
                retiredIds = transferResult.retiredIds.map { InstanceId(it) },
                zoneRecordings = transferResult.zoneRecordings.map { (iid, zid) -> InstanceId(iid) to zid },
                persistentBatch = remaining.batch,
                nextAnnotationId = remaining.nextAnnotationId,
            )

        val hasCastSpell = transferResult.transfers.any { it.category == TransferCategory.CastSpell }
        return BuildResult(built, hasCastSpell, mutations)
    }

    /**
     * Build a Diff [GameStateMessage] by snap-vs-snap field comparison.
     *
     * Genuinely pure on ordering-sensitive outputs: reads persistent state from
     * [cur.persistentAnnotationState] (not [bridge.annotations]); returns
     * [BridgeMutations] for the caller to apply via [GameBridge.applyMutations].
     *
     * `prev == null` → returns the Full GSM built from `cur` (first bundle, post-handshake).
     * Otherwise emits only zones/objects whose CardSnapshot/ZoneSnapshot field-equality
     * differs between `prev` and `cur`. Player/turn/annotation/persistent-annotation
     * lists are taken from the freshly-built current full GSM (current-bundle events
     * already applied).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition", "LongParameterList")
    fun buildDiff(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
    ): BuildResult {
        if (prev == null) {
            // First bundle — Full GSM with mutations returned for caller-apply.
            return buildFromSnapshot(
                cur,
                gameStateId,
                matchId,
                bridge,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                prev = null,
                events = events,
            )
        }

        // Library reveal forces a Full GSM. Library cards don't move zones or
        // change CardSnapshot fields when a search reveals them, so the diff
        // filter (snap-vs-snap object delta + zone-moved fids) discards them
        // and the picker can't render face-up. Sending Full re-emits every
        // game object including the library, with per-object visibility set
        // to Private + viewers=[searchingSeat] so only the searcher sees the
        // contents (matches the protocol shape Arena uses for cycling /
        // tutor searches).
        if (revealForSeat != null) {
            return buildFromSnapshot(
                cur,
                gameStateId,
                matchId,
                bridge,
                actions = actions,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                prev = prev,
                events = events,
            )
        }

        // Build current full GSM (viewingSeatId=0 to include all objects for accurate diff).
        val fullResult =
            buildFromSnapshot(
                cur,
                gameStateId,
                matchId,
                bridge,
                revealForSeat = revealForSeat,
                prev = prev,
                events = events,
            )
        val current = fullResult.gsm

        // Snap-vs-snap zone delta: any zone whose snapshot field-equality differs.
        val changedZoneIds =
            cur.zones.keys
                .asSequence()
                .filter { id -> prev.zones[id] != cur.zones[id] }
                .toSet()
        val opponentHandZoneId = ZoneMapper.opponentHandZone(viewingSeatId)
        val hasActiveReveal = bridge.allSeatIds().any { bridge.promptBridge(SeatId(it)).journal.activeReveal() != null }
        // Protocol-only zones not tracked in GsmSnapshot must always be included when non-empty:
        //   - Limbo (id=30): grows monotonically; always send when it has content.
        //   - REVEALED_P1/P2 (id=18/19): synthesized by applyRevealProxies during active reveal.
        //   - Hand zone of revealed seat: visibility flipped to Public by buildFromSnapshot but
        //     ZoneSnapshot still records Private, so snap equality check misses the change.
        val opponentRevealedHandZoneId: Int? =
            when {
                hasActiveReveal -> {
                    val ownerSeat =
                        bridge
                            .allSeatIds()
                            .firstNotNullOfOrNull {
                                bridge
                                    .promptBridge(SeatId(it))
                                    .journal
                                    .activeReveal()
                                    ?.ownerSeatId
                                    ?.value
                            }
                    ownerSeat?.let { ZoneIds.handOf(it) }
                }
                else -> null
            }
        val changedZones =
            current.zonesList.filter { zone ->
                zone.zoneId in changedZoneIds ||
                    (zone.zoneId == ZoneIds.LIMBO && zone.objectInstanceIdsCount > 0) ||
                    (zone.zoneId == ZoneIds.REVEALED_P1 || zone.zoneId == ZoneIds.REVEALED_P2) ||
                    (opponentRevealedHandZoneId != null && zone.zoneId == opponentRevealedHandZoneId)
            }

        // Snap-vs-snap object delta: any card whose CardSnapshot field-equality differs.
        // Plus opponent-hand filter + active-reveal exception preserved.
        val cardSnapshotChangedFids =
            cur.objects.keys
                .asSequence()
                .filter { fid -> prev.objects[fid] != cur.objects[fid] }
                .toSet()

        // Cards whose zone changed (CardSnapshot doesn't carry zoneId; ZoneSnapshot.contents does).
        val prevZoneOf: Map<ForgeCardId, Int> =
            prev.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val curZoneOf: Map<ForgeCardId, Int> =
            cur.zones.values
                .flatMap { z -> z.contents.map { it to z.id } }
                .toMap()
        val zoneMovedFids =
            (prevZoneOf.keys + curZoneOf.keys)
                .asSequence()
                .filter { fid -> prevZoneOf[fid] != curZoneOf[fid] }
                .toSet()

        val prevDisturbBackSourceFids = projectedDisturbBackSourceFids(prev)
        val curDisturbBackSourceFids = projectedDisturbBackSourceFids(cur)
        val changedFids = cardSnapshotChangedFids + zoneMovedFids
        val changedDisturbBackIds =
            disturbBackInstanceIds(
                changedFids.filter { it in prevDisturbBackSourceFids || it in curDisturbBackSourceFids },
                bridge,
            )
        val changedInstanceIds =
            changedFids.map { bridge.getOrAllocInstanceId(it).value }.toSet() + changedDisturbBackIds
        // instanceIds tracked in the prev snapshot (to detect truly new objects like RevealedCard proxies)
        val prevInstanceIds =
            prev.objects.keys
                .map { bridge.getOrAllocInstanceId(it).value }
                .toSet() +
                disturbBackInstanceIds(prevDisturbBackSourceFids, bridge)
        val changedObjects =
            current.gameObjectsList.filter { obj ->
                // Always include new objects absent from prev (e.g. RevealedCard proxies synthesized mid-diff).
                if (obj.instanceId !in prevInstanceIds) {
                    // Still apply opponent-hand filter unless reveal is active
                    if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                        return@filter hasActiveReveal && (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)
                    }
                    return@filter true
                }
                if (obj.instanceId !in changedInstanceIds) {
                    // During active reveal, always include opponent hand cards (visibility changed outside CardSnapshot)
                    if (hasActiveReveal &&
                        opponentHandZoneId != 0 &&
                        obj.zoneId == opponentHandZoneId &&
                        (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)
                    ) {
                        return@filter true
                    }
                    return@filter false
                }
                if (opponentHandZoneId != 0 && obj.zoneId == opponentHandZoneId) {
                    if (hasActiveReveal && (obj.type == GameObjectType.RevealedCard || obj.visibility == Visibility.Public)) {
                        // fall through
                    } else {
                        return@filter false
                    }
                }
                true
            }

        // Deleted IDs: in prev.objects but not in cur.objects, minus IDs still tracked
        // in cur zone listings (limbo-retired IDs that still appear in zone contents).
        val currentObjIds = current.gameObjectsList.map { it.instanceId }.toSet()
        val currentZoneTrackedIds = current.zonesList.flatMap { it.objectInstanceIdsList }.toSet()
        val deletedDisturbBackIds = disturbBackInstanceIds(prevDisturbBackSourceFids - curDisturbBackSourceFids, bridge)
        val deletedIds =
            ((prev.objects.keys - cur.objects.keys).map { bridge.getOrAllocInstanceId(it).value } + deletedDisturbBackIds)
                .filter { it !in currentObjIds && it !in currentZoneTrackedIds }

        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gameStateId)
                .setTurnInfo(current.turnInfo)
                .addAllPlayers(current.playersList)
                .addAllZones(changedZones.sortedBy { it.zoneId })
                .addAllGameObjects(changedObjects)
                .addAllAnnotations(current.annotationsList)
                // Emit only newly-added persistent annotations: the client accumulates
                // across diffs and removes via diffDeletedPersistentAnnotationIds. IDs
                // already present before this bundle's computeBatch are carried on the
                // client; re-sending them is redundant wire traffic that diverges from
                // the protocol spec. Baseline is cur's captured state (taken before
                // computeBatch ran), not prev's — prev predates the last apply.
                .addAllPersistentAnnotations(
                    current.persistentAnnotationsList.filter { it.id !in cur.persistentAnnotationState.activeAnnotations.keys },
                )
                // Drain THIS frame's deletions directly from the just-computed batch.
                // Reading from a queue populated by the prior frame's applyMutations
                // would lag deletes by one frame; end-of-stream value transitions
                // could then orphan their delete entirely.
                .addAllDiffDeletedPersistentAnnotationIds(fullResult.mutations.persistentBatch.deletedIds)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(updateType)
                .setPrevGameStateId(prev.gameStateId)

        // Fold any TriggerHolder gameObjects retired this GSM into the delete
        // list. The tracker queues them in `apply` (which ran inside the
        // wrapped buildFromSnapshot above) and we drain here so the client
        // retires the cached holder via instance-id.
        val holderDeletions = bridge.delayedTriggerHolders.drainDeletions()
        val allDeletedIds = deletedIds + holderDeletions
        if (allDeletedIds.isNotEmpty()) {
            builder.addAllDiffDeletedInstanceIds(allDeletedIds)
        }

        // Embed stripped actions + set pendingMessageCount when AAR follows
        if (actions != null) {
            builder.setPendingMessageCount(1)
            val activeSeat = current.turnInfo.priorityPlayer
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(activeSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        val built = builder.build()
        if (built.gameStateId != 0 && built.gameStateId == built.prevGameStateId) {
            log.error(
                "SELF-REF gsId={} prev.gsId={} param={} caller={}",
                built.gameStateId,
                prev.gameStateId,
                gameStateId,
                Thread.currentThread().stackTrace[2].let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
            )
        }
        return BuildResult(built, fullResult.hasCastSpell, fullResult.mutations)
    }

    /**
     * Resolve the correct updateType for a game state message.
     * - SendAndRecord: state change the client must persist (zone transfers, actions)
     * - SendHiFi: transient update (phase echoes, state refreshes)
     *
     * Note: protocol uses SendAndRecord for ALL zone-transfer diffs, regardless
     * of whose turn it is. This heuristic (acting == viewing) is an approximation
     * used by postAction; remoteActionDiff hardcodes SendHiFi directly.
     */
    fun resolveUpdateType(
        snap: GsmSnapshot,
        viewingSeatId: Int,
    ): GameStateUpdate {
        val actingSeat = snap.phase.priorityPlayer?.value ?: snap.phase.activePlayer.value
        return if (actingSeat == viewingSeatId) {
            GameStateUpdate.SendAndRecord
        } else {
            GameStateUpdate.SendHiFi
        }
    }

    /** Assemble the final GameStateMessage proto from computed components. */
    @Suppress("LongParameterList")
    private fun assembleGsm(
        gameStateId: Int,
        gameInfo: GameInfo,
        frame: GsmFrame,
        transferResult: TransferResult,
        remaining: RemainingAnnotationsResult,
        combatResult: CombatAnnotationResult,
        team1: TeamInfo,
        team2: TeamInfo,
        player1: PlayerInfo,
        player2: PlayerInfo,
        updateType: GameStateUpdate,
        actions: ActionsAvailableReq?,
        prioritySeat: Int,
        prevGsId: Int?,
    ): GameStateMessage {
        val effectiveTurnInfo =
            if (combatResult.hasCombatDamage) {
                frame
                    .turnInfo()
                    .toBuilder()
                    .setPhase(Phase.Combat_a549)
                    .setStep(combatResult.damageStep)
            } else {
                frame.turnInfo().toBuilder()
            }

        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(gameStateId)
                .setGameInfo(gameInfo)
                .addAllTeams(listOf(team1, team2))
                .setTurnInfo(effectiveTurnInfo)
                .addAllPlayers(listOf(player1, player2))
                .addAllZones(transferResult.patchedZones.sortedBy { it.zoneId })
                .addAllGameObjects(transferResult.patchedObjects)
                .addAllAnnotations(remaining.numbered)
                .addAllPersistentAnnotations(remaining.persistent)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(updateType)
        if (prevGsId != null && prevGsId > 0) {
            builder.setPrevGameStateId(prevGsId)
        }

        if (actions != null) {
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(prioritySeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }
        return builder.build()
    }

    private fun projectedDisturbBackSourceFids(snap: GsmSnapshot): Set<ForgeCardId> {
        val playerZoneFids =
            disturbBackPlayerZoneIds
                .asSequence()
                .flatMap { zoneId ->
                    snap.zones[zoneId]
                        ?.contents
                        .orEmpty()
                        .asSequence()
                }

        return playerZoneFids
            .filter { fid ->
                val cardSnap = snap.objects[fid] ?: return@filter false
                cardSnap.othersideGrpId != 0 &&
                    snap.boundCards[fid]?.altCost(KeywordAbilityIds.DISTURB) != null
            }.toSet()
    }

    private fun disturbBackInstanceIds(
        sourceFids: Iterable<ForgeCardId>,
        bridge: GameBridge,
    ): Set<Int> =
        sourceFids
            .mapTo(mutableSetOf()) { fid ->
                bridge.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(fid)).value
            }

    /** Result of stages 4-5 + persistent annotation computation. */
    private data class RemainingAnnotationsResult(
        val numbered: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        val batch: PersistentAnnotationStore.BatchResult,
        val nextAnnotationId: Int,
    )

    /** Stages 4-5: mechanic + effect annotations, persistent computation, numbering. */
    @Suppress("LongParameterList", "LongMethod")
    private fun computeRemainingAnnotations(
        events: List<GameEvent>,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: List<AnnotationInfo>,
        initEffectDiff: EffectTracker.DiffResult,
        effectDiff: EffectTracker.DiffResult,
        persistSnapshot: Map<Int, AnnotationInfo>,
        startPersistentId: Int,
        startAnnotationId: Int,
        bridge: GameBridge,
        frameContext: FrameContext,
        frameIds: FrameIdResolver,
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        combatResult: CombatAnnotationResult = CombatAnnotationResult(emptyList()),
        qualificationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        temporaryPermanentPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        delayedTriggerAffecteesPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        abilityWordPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        preparedDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        plottedDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        saddledDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        commanderDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        leftUnlockedDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        rightUnlockedDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        dayNightDesignationPersistentFromSnap: List<AnnotationInfo> = emptyList(),
        faceDownDisguisePersistentFromSnap: List<AnnotationInfo> = emptyList(),
    ): RemainingAnnotationsResult {
        val castSpellManaForgeIds =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .flatMap { it.manaPayments.map { mp -> mp.sourceCardId } }
                .toSet()
        val sacrificedManaForgeIds =
            events
                .filterIsInstance<GameEvent.ManaAbilityActivated>()
                .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.cardId == ma.cardId } }
                .map { it.cardId }
                .toSet()
        val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds
        val mechanicResult =
            MechanicAnnotations.mechanicAnnotations(
                events,
                manaPaidForgeCardIds,
                idResolver = { fid -> frameIds.cardIid(fid) },
                effectIdAllocator = { leyline.bridge.types.EffectId(bridge.effects.nextEffectId()) },
                activeStealForgeCardIds = bridge.annotations.activeStealForgeCardIds(),
                manaAbilityGrpIdResolver = { fid ->
                    val card = bridge.getGame()?.let { leyline.bridge.findCard(it, fid) }
                    val grpId =
                        if (card != null) {
                            val subtypes = card.type.subtypes.map { it.lowercase() }
                            leyline.game.data.BasicLandAbilities.BY_SUBTYPE
                                .firstOrNull { it.first in subtypes }
                                ?.second ?: 0
                        } else {
                            0
                        }
                    leyline.bridge.types.GrpId(grpId)
                },
                counterAffectorResolver = { eventIndex, ev -> counterAffectorFor(eventIndex, ev, events, frameIds) },
            )
        // Token entries belong before combat damage: a Mobilize trigger that
        // resolves between attacker declaration and combat damage produces tokens
        // that themselves attack and deal damage. The client identity map needs
        // them in place before processing the DamageDealt entries that reference
        // their iids — otherwise the tokens visually pop in after first damage
        // animates. Other mechanic annotations (counters, scry, surveil, …) keep
        // their post-combat slot since they typically come from damage-triggered
        // effects.
        val (tokenCreatedAnns, otherMechanic) =
            mechanicResult.transient.partition { ann ->
                AnnotationType.TokenCreated in ann.typeList
            }
        if (tokenCreatedAnns.isNotEmpty()) {
            val firstDamageIdx =
                annotations.indexOfFirst { ann ->
                    AnnotationType.DamageDealt_af5a in ann.typeList
                }
            if (firstDamageIdx >= 0) {
                annotations.addAll(firstDamageIdx, tokenCreatedAnns)
            } else {
                annotations.addAll(tokenCreatedAnns)
            }
        }
        annotations.addAll(otherMechanic)

        // AbilityWordActive: consumed from pre-computed snap entries
        val abilityWordPersistent = abilityWordPersistentFromSnap

        if (initEffectDiff.created.isNotEmpty()) {
            val (initTransient, _) = MechanicAnnotations.effectAnnotations(initEffectDiff)
            annotations.addAll(initTransient)
        }

        val sourceAbilityResolver = SourceAbilityResolverFactory.build(bridge)
        val (effectTransient, effectPersistent) =
            MechanicAnnotations.effectAnnotations(
                diff = effectDiff,
                sourceAbilityResolver = sourceAbilityResolver,
                keywordDiff = keywordDiff,
                keywordAffectorResolver = { _, _, _ ->
                    // Best-effort: use most recent SpellResolved event's instanceId as affector.
                    // Full resolver (tracing spell → keyword grant) comes later.
                    events
                        .filterIsInstance<GameEvent.SpellResolved>()
                        .lastOrNull()
                        ?.let { bridge.getOrAllocInstanceId(it.cardId) }
                        ?: leyline.bridge.types.InstanceId(0)
                },
                uniqueAbilityIdAllocator = { bridge.effects.nextEffectId() },
            )
        annotations.addAll(effectTransient)

        // Qualification pAnn for adventure-exiled cards (cast-from-exile eligibility marker)
        val qualificationPersistent = qualificationPersistentFromSnap

        // TemporaryPermanent pAnn for any token with EOT-sacrifice (copy or otherwise)
        val temporaryPermanentPersistent = temporaryPermanentPersistentFromSnap

        // DelayedTriggerAffectees groups EOT-sacrifice tokens that share a
        // delayed trigger (Mobilize, EOT-sacrifice copies). One annotation per
        // group, persistent until the trigger resolves.
        val delayedTriggerAffecteesPersistent = delayedTriggerAffecteesPersistentFromSnap

        // TargetSpec pAnn for each targeted spell/ability on the stack
        val targetSpecPersistent = buildTargetSpecAnnotations(bridge, frameIds)

        val (crewedThisTurnPersistent, crewTypeChangePersistent, crewExpiredAnnotations) =
            computeCrewAnnotations(bridge)
        val saddledThisTurnPersistent = computeSaddleAnnotations(bridge)
        annotations.addAll(crewExpiredAnnotations)

        val enrichedMechanicResult =
            mechanicResult.copy(
                perKindPersistent =
                    buildMap<PersistentAnnotationKind, List<AnnotationInfo>> {
                        put(AbilityWordActiveKind, abilityWordPersistent)
                        put(
                            QualificationKind,
                            qualificationPersistent +
                                mechanicResult.perKindPersistent[QualificationKind].orEmpty(),
                        )
                        put(CrewedThisTurnKind, crewedThisTurnPersistent)
                        put(SaddledThisTurnKind, saddledThisTurnPersistent)
                        put(ModifiedTypeForCrewKind, crewTypeChangePersistent)
                        put(TemporaryPermanentKind, temporaryPermanentPersistent)
                        put(DelayedTriggerAffecteesKind, delayedTriggerAffecteesPersistent)
                        put(TargetSpecKind, targetSpecPersistent)
                        put(PreparedDesignationKind, preparedDesignationPersistentFromSnap)
                        put(PlottedDesignationKind, plottedDesignationPersistentFromSnap)
                        put(CommanderDesignationKind, commanderDesignationPersistentFromSnap)
                        put(SaddledDesignationKind, saddledDesignationPersistentFromSnap)
                        put(LeftUnlockedDesignationKind, leftUnlockedDesignationPersistentFromSnap)
                        put(RightUnlockedDesignationKind, rightUnlockedDesignationPersistentFromSnap)
                        put(DayNightDesignationKind, dayNightDesignationPersistentFromSnap)
                        put(FaceDownDisguiseKind, faceDownDisguisePersistentFromSnap)
                    },
            )
        val batch =
            PersistentAnnotationStore.Companion.computeBatch(
                currentActive = persistSnapshot,
                startPersistentId = startPersistentId,
                frame = frameContext,
                effectPersistent = effectPersistent,
                effectDiff = effectDiff,
                transferPersistent = transferPersistent,
                mechanicResult = enrichedMechanicResult,
                combatResult = combatResult,
                resolveInstanceId = { fid -> bridge.getOrAllocInstanceId(fid) },
                resolveForgeCardId = { iid -> bridge.getForgeCardId(iid) },
            )

        // Emit LayeredEffectDestroyed for reverted steals
        for (effectId in batch.revertedEffectIds) {
            annotations.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        // Track steal lifecycle
        bridge.annotations.addSteals(mechanicResult.controllerChangedEffects.map { it.forgeCardId })
        bridge.annotations.removeSteals(mechanicResult.controllerRevertedForgeCardIds)

        val ordered = AnnotationOrderEnforcer.enforce(annotations)
        var annId = startAnnotationId
        val numbered = ordered.map { it.toBuilder().setId(annId++).build() }
        return RemainingAnnotationsResult(numbered, batch.allAnnotations, batch, annId)
    }

    /** Stages 2-3 of the annotation pipeline: transfers → annotations + combat. */
    internal data class AnnotationPipelineResult(
        val annotations: MutableList<AnnotationInfo>,
        val transferPersistent: MutableList<AnnotationInfo>,
        val combatResult: CombatAnnotationResult,
    )

    /**
     * Assemble stages 2-3 around the key invariant for lethal damage:
     * DamageDealt must land before the victim's destroy transfer.
     */
    internal fun assembleTransferAndCombatAnnotations(
        events: List<GameEvent>,
        transferResult: TransferResult,
        actingSeat: Int,
        combatResult: CombatAnnotationResult,
        bridge: GameBridge? = null,
        snap: GsmSnapshot? = null,
        frameIds: FrameIdResolver? = null,
    ): Pair<MutableList<AnnotationInfo>, MutableList<AnnotationInfo>> {
        val annotations = mutableListOf<AnnotationInfo>()
        val transferPersistent = mutableListOf<AnnotationInfo>()
        val lethalDamageVictims =
            events
                .filterIsInstance<GameEvent.DamageDealtToCard>()
                .map { it.targetCardId }
                .toSet()
        // Activated-ability affector map: forgeCardId → queue of AB iids. Stamps
        // affectorId on cost-paid transfers (cycling discard, channel discard,
        // unearth GY→BF return) so the resulting annotation ties the transfer
        // back to the ability instance that caused it.
        //
        // Restricted to categories that are plausibly the "ability put X here"
        // outcome — Discard (cycling/channel cost), Resolve (unearth GY→BF) —
        // so an unrelated transfer for the same card later in the same frame
        // (e.g., a bounce trigger BF→Hand) doesn't get the AB iid stamped.
        //
        // Backed by a mutable per-card queue so chained activations on the
        // same card in one frame burn distinct AB iids in order; the same
        // forgeCardId stamping the same iid twice would conflate transfers.
        val activatedAbilityAffectors: MutableMap<ForgeCardId, ArrayDeque<Int>> =
            if (frameIds != null) {
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .filter { it.isAbility && !it.isTrigger }
                    .map { it.cardId to stackAbilityIidFor(it.abilityForgeId, it.cardId, frameIds) }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, iids) -> ArrayDeque(iids) }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        val patchedTransfers =
            if (activatedAbilityAffectors.isEmpty()) {
                transferResult.transfers
            } else {
                transferResult.transfers.map { transfer ->
                    val canCarryAffector =
                        transfer.affectorId == 0 &&
                            (
                                transfer.category == TransferCategory.Discard ||
                                    transfer.category == TransferCategory.Resolve
                            )
                    if (!canCarryAffector) return@map transfer
                    val abIid = transfer.forgeCardId?.let { activatedAbilityAffectors[it]?.removeFirstOrNull() }
                    if (abIid != null) transfer.copy(affectorId = abIid) else transfer
                }
            }
        val (deferredTransfers, immediateTransfers) =
            patchedTransfers.partition { transfer ->
                transfer.category == TransferCategory.Destroy &&
                    transfer.forgeCardId != null &&
                    transfer.forgeCardId in lethalDamageVictims
            }

        fun emitTransfer(transfer: AppliedTransfer) {
            val (transient, persistent) = TransferAnnotations.annotationsForTransfer(transfer, SeatId(actingSeat))
            annotations.addAll(transient)
            transferPersistent.addAll(persistent)
        }

        for (transfer in immediateTransfers) emitTransfer(transfer)
        // Snapshot-derived appearances (cast spells visible on the stack at snapshot time).
        val snapshotSourceIids = transferResult.stackAbilityAppearances.map { it.sourceCardInstanceId }.toSet()
        for (a in transferResult.stackAbilityAppearances) {
            // Snapshot-derived sourceZoneId reads `previousZones[sourceCardIid]`
            // which is 0 when the source card wasn't tracked through the diff
            // (puzzle-injected starting state). For activated abilities the
            // SpellCast event carries an explicit activationZoneId — prefer it
            // when non-zero so cycling=Hand and unearth=Graveyard both land.
            val sourceZone = if (a.activationZoneId != 0) a.activationZoneId else a.sourceZoneId
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(a.abilityInstanceId),
                    InstanceId(a.sourceCardInstanceId),
                    sourceZone,
                ),
            )
            // TriggeringObject is trigger-only — activated abilities (cycling,
            // channel, unearth, …) do not carry one in the protocol shape.
            if (!a.isActivatedAbility) {
                transferPersistent.add(
                    AnnotationBuilder.triggeringObject(
                        abilityInstanceId = InstanceId(a.abilityInstanceId),
                        sourceCardInstanceId = InstanceId(a.sourceCardInstanceId),
                        sourceZone = sourceZone,
                    ),
                )
            }
        }
        val snapshotDisappearanceIids = transferResult.stackAbilityDisappearances.map { it.abilityInstanceId }.toSet()
        // Event-driven trigger lifecycle. With auto-pass on the local turn the
        // trigger can fire and resolve between two snapshots so the snap-diff
        // misses both halves; with the per-trigger GSM split (GamePlayback)
        // each half lands in its own GSM, so cast and resolve events arrive in
        // separate drains. We emit the cast-side annotations from cast events
        // and the resolve-side from resolve events independently — guarding
        // against double-emission when the snap-diff also caught the
        // appearance/disappearance.
        if (bridge != null && snap != null) {
            emitTriggerLifecycleAnnotations(
                events = events,
                snapshotSourceIids = snapshotSourceIids,
                snapshotDisappearanceIids = snapshotDisappearanceIids,
                annotations = annotations,
                transferPersistent = transferPersistent,
                bridge = bridge,
                snap = snap,
                frameIds = frameIds ?: FrameIdResolver(bridge),
            )
        }
        for (d in transferResult.stackAbilityDisappearances) {
            val lineage = bridge?.abilityLineage?.consume(d.abilityInstanceId)
            val sourceCardInstanceId = lineage?.sourceIidAtCreate ?: d.sourceCardInstanceId
            annotations.add(
                AnnotationBuilder.abilityInstanceDeleted(
                    InstanceId(d.abilityInstanceId),
                    InstanceId(sourceCardInstanceId),
                ),
            )
        }
        for (ev in events.filterIsInstance<GameEvent.PhaseChanged>()) {
            annotations.add(AnnotationBuilder.phaseOrStepModified(ev.seatId, ev.phase, ev.step))
        }
        annotations.addAll(combatResult.annotations)
        for (transfer in deferredTransfers) emitTransfer(transfer)
        return annotations to transferPersistent
    }

    /**
     * Emit AbilityInstanceCreated / TriggeringObject / ResolutionStart-Complete /
     * AbilityInstanceDeleted for triggered abilities that surfaced via the event
     * stream but were missed by snapshot-diff (auto-resolved between snapshots).
     *
     * The stack ability instanceId comes from
     * [FrameIdResolver.triggerStackAbilityIid] keyed on the event's
     * `abilityForgeId` so back-to-back triggers from one source card mint
     * distinct iids; falls back to source-card-keyed surrogate when the
     * collector didn't surface the SA id (legacy paths, defensive 0).
     */
    private fun emitTriggerLifecycleAnnotations(
        events: List<GameEvent>,
        snapshotSourceIids: Set<Int>,
        snapshotDisappearanceIids: Set<Int>,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: MutableList<AnnotationInfo>,
        bridge: GameBridge,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ) {
        // Cast half: AbilityInstanceCreated (when snap-diff missed it) + persistent TriggeringObject.
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isTrigger }) {
            val sourceCardIid = frameIds.cardIid(cast.cardId).value
            val abilityIid = stackAbilityIidFor(cast.abilityForgeId, cast.cardId, frameIds)
            val sourceZone = currentSourceZoneId(cast.cardId, bridge)

            if (sourceCardIid in snapshotSourceIids) continue
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: abilityGrpIdForSource(cast.cardId, cast.abilityForgeId, bridge, snap),
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(abilityIid),
                    InstanceId(sourceCardIid),
                    sourceZone,
                ),
            )
            transferPersistent.add(
                AnnotationBuilder.triggeringObject(
                    abilityInstanceId = InstanceId(abilityIid),
                    sourceCardInstanceId = InstanceId(sourceCardIid),
                    sourceZone = sourceZone,
                ),
            )
        }

        // Activated-ability cast half: AbilityInstanceCreated keyed off the
        // event's activationZoneId (cycling=Hand, unearth=Graveyard, …).
        // No persistent TriggeringObject — that annotation is specific to
        // triggered abilities.
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isAbility && !it.isTrigger }) {
            val sourceCardIid = frameIds.cardIid(cast.cardId).value
            val abilityIid = stackAbilityIidFor(cast.abilityForgeId, cast.cardId, frameIds)
            val sourceZone =
                if (cast.activationZoneId != 0) cast.activationZoneId else currentSourceZoneId(cast.cardId, bridge)

            if (sourceCardIid in snapshotSourceIids) continue
            bridge.abilityLineage.record(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: abilityGrpIdForSource(cast.cardId, cast.abilityForgeId, bridge, snap),
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(abilityIid),
                    InstanceId(sourceCardIid),
                    sourceZone,
                ),
            )
        }

        // Resolve half: ResolutionStart/Complete (always — snap-diff doesn't emit
        // these for stack-only abilities) + AbilityInstanceDeleted (when snap-diff
        // missed it). Same shape applies for triggered and activated abilities.
        for (resolved in events.filterIsInstance<GameEvent.SpellResolved>().filter { it.isTrigger || it.isAbility }) {
            val sourceCardIid = frameIds.cardIid(resolved.cardId).value
            val abilityIid = stackAbilityIidFor(resolved.abilityForgeId, resolved.cardId, frameIds)
            val lineage =
                if (abilityIid in snapshotDisappearanceIids) {
                    bridge.abilityLineage.find(abilityIid)
                } else {
                    bridge.abilityLineage.consume(abilityIid)
                }
            val aidSourceIid = lineage?.sourceIidAtCreate ?: sourceCardIid
            val abilityGrpId =
                lineage?.abilityGrpId?.takeIf { it != 0 }
                    ?: resolved.abilityGrpId.takeIf { it != 0 }
                    ?: abilityGrpIdForSource(resolved.cardId, resolved.abilityForgeId, bridge, snap)

            annotations.add(AnnotationBuilder.resolutionStart(InstanceId(abilityIid), GrpId(abilityGrpId)))
            annotations.add(AnnotationBuilder.resolutionComplete(InstanceId(abilityIid), GrpId(abilityGrpId)))
            if (abilityIid !in snapshotDisappearanceIids) {
                annotations.add(
                    AnnotationBuilder.abilityInstanceDeleted(
                        InstanceId(abilityIid),
                        InstanceId(aidSourceIid),
                    ),
                )
            }
        }
    }

    /**
     * SA-id-keyed surrogate iid for a stack-resident trigger or activated
     * ability, with source-card fallback when the collector didn't surface
     * the SA id (defensive 0). Both lifecycle paths share this minter so a
     * single AB iid threads through Created → ZoneTransfer affector → Deleted.
     */
    private fun stackAbilityIidFor(
        forgeAbilityId: Int,
        sourceForgeId: ForgeCardId,
        frameIds: FrameIdResolver,
    ): Int =
        if (forgeAbilityId != 0) {
            frameIds.triggerStackAbilityIid(forgeAbilityId).value
        } else {
            frameIds.stackAbilityIid(sourceForgeId).value
        }

    /** Best-effort source-zone lookup for an event-derived trigger. Falls back
     *  to Battlefield (28) — the dominant case for combat / state-change triggers.
     *  ZoneType has many rarely-used values (Sideboard, Ante, Subgame…) that
     *  don't host triggering objects we'd surface to the wire; mapping each
     *  is noise. The else-branch keeps the fallback explicit. */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun currentSourceZoneId(
        cardId: ForgeCardId,
        bridge: GameBridge,
    ): Int {
        val card = bridge.findCard(cardId) ?: return ZoneIds.BATTLEFIELD
        val ownerSeat = ownerSeatOf(card, bridge)
        return when (card.zone?.zoneType) {
            ForgeZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ForgeZoneType.Stack -> ZoneIds.STACK
            ForgeZoneType.Graveyard -> ZoneIds.graveyardOf(ownerSeat)
            ForgeZoneType.Exile -> ZoneIds.EXILE
            ForgeZoneType.Hand -> ZoneIds.handOf(ownerSeat)
            ForgeZoneType.Library -> ZoneIds.libraryOf(ownerSeat)
            ForgeZoneType.Command -> ZoneIds.COMMAND
            else -> ZoneIds.BATTLEFIELD
        }
    }

    /** Look up the outbound ability grpId for a triggered source. For known
     *  keyword triggers (Mobilize, …) this resolves to the per-card keyword
     *  ability grpId — e.g. 188698 for a Mobilize 1 source — so
     *  `ResolutionStart`/`Complete` carry the keyword row id rather than the
     *  source card's grpId. Falls back to the source card's grpId for triggers
     *  whose keyword isn't in [leyline.game.data.KeywordAbilityIds] yet. */
    private fun abilityGrpIdForSource(
        cardId: ForgeCardId,
        abilityForgeId: Int,
        bridge: GameBridge,
        snap: GsmSnapshot,
    ): Int {
        val bound = snap.boundCards[cardId] ?: return 0
        if (abilityForgeId != 0) {
            val card = bridge.findCard(cardId)
            val registry = if (card != null) bridge.abilityRegistryFor(card, bound.data) else null
            registry?.forSpellAbility(abilityForgeId)?.takeIf { it != 0 }?.let { return it }
            registry?.forTrigger(abilityForgeId)?.takeIf { it != 0 }?.let { return it }
        }
        for (keywordId in keywordTriggerIds) {
            bound.altCost(keywordId)?.abilityGrpId?.let { return it }
            bridge.cardRepository.findKeywordAbilityGrpId(bound.snapshot.grpId, keywordId)?.let { return it }
        }
        return bound.snapshot.grpId
    }

    private fun TransferResult.withDecayedCleanupAffectors(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): TransferResult {
        val cleanupAbilityIids =
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.isTrigger && it.abilityGrpId != 0 }
                .mapNotNull { ev ->
                    val cleanupGrpId = decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, this) ?: return@mapNotNull null
                    if (ev.abilityGrpId != cleanupGrpId) return@mapNotNull null
                    ev.cardId to stackAbilityIidFor(ev.abilityForgeId, ev.cardId, frameIds)
                }.toMap()
        if (cleanupAbilityIids.isEmpty()) return this
        val patchedTransfers =
            transfers.map { transfer ->
                val affector = transfer.forgeCardId?.let { cleanupAbilityIids[it] } ?: return@map transfer
                if (transfer.affectorId != 0) return@map transfer
                val category =
                    if (transfer.srcZoneId == ZoneIds.BATTLEFIELD && transfer.destZoneId in graveyardZoneIds) {
                        TransferCategory.Sacrifice
                    } else {
                        transfer.category
                    }
                transfer.copy(category = category, affectorId = affector)
            }
        return copy(transfers = patchedTransfers)
    }

    private val graveyardZoneIds = setOf(ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD)

    private fun updateDecayedCleanupSources(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        transferResult: TransferResult,
    ): Set<ForgeCardId> {
        val visibleThisGsm = bridge.activeDecayedCleanupSources().toMutableSet()
        val addedThisGsm = linkedSetOf<ForgeCardId>()
        for (ev in events) {
            if (ev is GameEvent.SpellResolved) {
                val cleanupGrpId = decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: abilityGrpIdForSource(ev.cardId, ev.abilityForgeId, bridge, snap)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == KeywordAbilityIds.DECAYED) {
                    bridge.recordDecayedCleanupSource(ev.cardId)
                    visibleThisGsm.add(ev.cardId)
                    addedThisGsm.add(ev.cardId)
                }
            } else if (ev is GameEvent.SpellCast) {
                val cleanupGrpId = decayedCleanupGrpIdForSource(ev.cardId, snap, bridge, transferResult)
                val abilityGrpId =
                    ev.abilityGrpId.takeIf { it != 0 }
                        ?: abilityGrpIdForSource(ev.cardId, ev.abilityForgeId, bridge, snap)
                if (ev.isTrigger && cleanupGrpId != null && abilityGrpId == cleanupGrpId) {
                    bridge.clearDecayedCleanupSource(ev.cardId)
                    if (ev.cardId !in addedThisGsm) visibleThisGsm.remove(ev.cardId)
                }
            } else if (ev is GameEvent.CardSacrificed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.CardDestroyed) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.CardBounced) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.CardExiled) {
                clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
            } else if (ev is GameEvent.ZoneChanged) {
                if (ev.from == leyline.game.event.Zone.Battlefield && ev.to != leyline.game.event.Zone.Battlefield) {
                    clearDecayedCleanupSource(ev.cardId, addedThisGsm, visibleThisGsm, bridge)
                }
            }
        }
        return visibleThisGsm
    }

    private fun clearDecayedCleanupSource(
        sourceForgeId: ForgeCardId,
        addedThisGsm: Set<ForgeCardId>,
        visibleThisGsm: MutableSet<ForgeCardId>,
        bridge: GameBridge,
    ) {
        bridge.clearDecayedCleanupSource(sourceForgeId)
        if (sourceForgeId !in addedThisGsm) visibleThisGsm.remove(sourceForgeId)
    }

    private fun decayedCleanupHoldersFromSnap(
        sourceForgeIds: Set<ForgeCardId>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
        transferResult: TransferResult,
    ): List<HolderRecord> =
        sourceForgeIds.mapNotNull { sourceForgeId ->
            val bound = snap.boundCards[sourceForgeId]
            val transfer = transferResult.transfers.firstOrNull { it.forgeCardId == sourceForgeId }
            val sourceIid = decayedCleanupSourceIid(sourceForgeId, bound?.snapshot, frameIds, transferResult) ?: return@mapNotNull null
            val cleanupGrpId = decayedCleanupGrpIdForSource(sourceForgeId, snap, bridge, transferResult) ?: return@mapNotNull null
            HolderRecord(
                iid = holderInstanceIdFor(sourceForgeId, bridge).value,
                ownerSeat = bound?.snapshot?.controller?.value ?: transfer?.ownerSeatId ?: return@mapNotNull null,
                objectSourceGrpId = KeywordAbilityIds.DECAYED,
                parentIid = sourceIid,
                cleanupGrpId = cleanupGrpId,
            )
        }

    private fun decayedCleanupSourceIid(
        sourceForgeId: ForgeCardId,
        source: CardSnapshot?,
        frameIds: FrameIdResolver,
        transferResult: TransferResult,
    ): Int? {
        if (source?.isOnBattlefield == true) return frameIds.cardIid(sourceForgeId).value
        val transfer =
            transferResult.transfers.firstOrNull {
                it.forgeCardId == sourceForgeId && it.category == TransferCategory.Sacrifice
            }
        return transfer?.origId
    }

    /** Training's `AbilityWordActive` is relational: trainer → first declared
     *  co-attacker with greater pre-resolution power. The previous snapshot is
     *  the attack-declaration truth when auto-pass compresses trigger resolution
     *  and counter placement into one GSM. */
    private fun trainingAbilityWordPersistentFromEvents(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val attackEvents = events.filterIsInstance<GameEvent.AttackersDeclared>()
        if (attackEvents.isEmpty()) return emptyList()
        val powerSnap = prev ?: snap

        return attackEvents.flatMap { ev ->
            ev.attackerCardIds.mapNotNull { trainerId ->
                val trainerBound = powerSnap.boundCards[trainerId] ?: snap.boundCards[trainerId] ?: return@mapNotNull null
                if (!hasTrainingKeyword(trainerBound.snapshot.grpId, bridge)) return@mapNotNull null
                val trainerPower = trainerBound.snapshot.netPower ?: return@mapNotNull null
                val partnerId =
                    ev.attackerCardIds.firstOrNull { otherId ->
                        if (otherId == trainerId) return@firstOrNull false
                        val otherBound = powerSnap.boundCards[otherId] ?: snap.boundCards[otherId] ?: return@firstOrNull false
                        (otherBound.snapshot.netPower ?: Int.MIN_VALUE) > trainerPower
                    } ?: return@mapNotNull null

                val trainerIid = frameIds.cardIid(trainerId)
                AnnotationBuilder.abilityWordActive(
                    instanceId = trainerIid,
                    abilityWordName = "Training",
                    affectorId = trainerIid,
                    affectedIds = listOf(frameIds.cardIid(partnerId)),
                )
            }
        }
    }

    private fun hasTrainingKeyword(
        grpId: Int,
        bridge: GameBridge,
    ): Boolean = bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.TRAINING) != null

    private fun counterAffectorFor(
        eventIndex: Int,
        ev: GameEvent.CountersChanged,
        events: List<GameEvent>,
        frameIds: FrameIdResolver,
    ): InstanceId? {
        val resolvedTraining = trainingResolutionForCounterEvent(eventIndex, ev, events) ?: return null
        return InstanceId(stackAbilityIidFor(resolvedTraining.abilityForgeId, resolvedTraining.cardId, frameIds))
    }

    internal fun trainingResolutionForCounterEvent(
        eventIndex: Int,
        ev: GameEvent.CountersChanged,
        events: List<GameEvent>,
    ): GameEvent.SpellResolved? {
        if (ev.counterType != "P1P1" && ev.counterType != "+1/+1") return null
        for (next in events.asSequence().drop(eventIndex + 1)) {
            when {
                next is GameEvent.CountersChanged && next.cardId == ev.cardId -> return null
                next is GameEvent.SpellResolved && next.cardId == ev.cardId -> {
                    if (next.isTrigger && next.abilityGrpId == KeywordAbilityIds.TRAINING) {
                        return next
                    }
                    return null
                }
            }
        }
        return null
    }

    /** Keywords whose triggers we want to surface on the wire as
     *  `ResolutionStart`/`Complete grpid = <keyword ability id>`. Extend as new
     *  combat/ETB/state-trigger keywords ship and need precise grpId fidelity. */
    private val keywordTriggerIds = listOf(KeywordAbilityIds.MOBILIZE, KeywordAbilityIds.DECAYED)

    /** Forge id of the source card that spawned [tokenForgeId], or null when
     *  the token has no `tokenSpawningAbility` (puzzle-injected tokens, copy
     *  tokens, etc.). Used by EOT-cleanup pAnn emission to derive both the
     *  cleanup ability grpId and the trigger-holder iid. */
    private fun tokenSourceForgeId(
        tokenForgeId: ForgeCardId,
        bridge: GameBridge,
    ): ForgeCardId? {
        val tokenCard = bridge.findCard(tokenForgeId) ?: return null
        val sourceCard = tokenCard.tokenSpawningAbility?.hostCard ?: return null
        return ForgeCardId(sourceCard.id)
    }

    /** See [BoundCard.mobilizeCleanup]. Null when the source isn't a Mobilize
     *  card or has no hidden triggered ability; callers fall back to the
     *  universal EOT-sacrifice grpId. */
    private fun mobilizeCleanupGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
    ): Int? = snap.boundCards[sourceForgeId]?.mobilizeCleanup

    /** The Mobilize keyword ability grpId on a Mobilize source (188696, 188698…),
     *  used as `objectSourceGrpId` on the TriggerHolder gameObject so the client
     *  renders the right ability icon and tooltip text in the timed-effect
     *  side panel. Null when the source doesn't carry Mobilize. */
    private fun mobilizeKeywordGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
    ): Int? =
        snap.boundCards[sourceForgeId]
            ?.altCost(leyline.game.data.KeywordAbilityIds.MOBILIZE)
            ?.abilityGrpId

    private fun decayedCleanupGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
        bridge: GameBridge,
        transferResult: TransferResult? = null,
    ): Int? {
        snap.boundCards[sourceForgeId]?.decayedCleanup?.let { return it }
        val grpId = transferResult?.transfers?.firstOrNull { it.forgeCardId == sourceForgeId }?.grpId ?: return null
        if (bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.DECAYED) == null) return null
        return bridge.cardRepository.findHiddenTriggeredAbilityGrpId(grpId)
    }

    /** Stable per-trigger-registration holder iid keyed on the source card's
     *  forge id, so all tokens spawned by the same source-card resolution
     *  share one holder (matching the canonical Mobilize 3 →
     *  one-holder-three-tokens shape). Caller resolves [sourceForgeId] via
     *  [tokenSourceForgeId] before invoking — currently every TriggerHolder
     *  emit path requires a known source. */
    private fun holderInstanceIdFor(
        sourceForgeId: ForgeCardId,
        bridge: GameBridge,
    ): InstanceId {
        val holderForge = ForgeCardId(sourceForgeId.value + GameBridge.DELAYED_TRIGGER_HOLDER_FORGE_OFFSET)
        return bridge.getOrAllocInstanceId(holderForge)
    }

    /** Best-effort owner seat lookup for an event-derived source card. */
    private fun ownerSeatOf(
        card: forge.game.card.Card,
        bridge: GameBridge,
    ): Int {
        val owner = card.owner ?: return 1
        return if (owner.lobbyPlayer is forge.ai.LobbyPlayerAi) {
            bridge.seating.familiarSeat.value
        } else {
            bridge.seating.humanSeat.value
        }
    }

    /**
     * Scan the stack for spells/abilities with targets and emit TargetSpec pAnns.
     * Each card target gets a separate annotation with 1-based index per target group.
     * Pruned automatically by the registry-driven upsert pass (TargetSpecKind's
     * full-replacement semantics) when the spell resolves and leaves the stack.
     */
    private fun buildTargetSpecAnnotations(
        bridge: GameBridge,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        // Drain target picks recorded during selectTargetsInteractively.
        // The spell may have already resolved by now (auto-pass), so we can't
        // rely on scanning game.getStack() — the stack is often empty.
        val pending = bridge.drainPendingTargetSpecs()
        if (pending.isEmpty()) return emptyList()

        // TODO: abilityGrpId needs sub-ability registry lookup, promptId needs
        //  prompt-type mapping. Both require deeper card-DB plumbing. Falls
        //  back to card grpId and 0 until wired.
        return pending.mapNotNull { spec ->
            // Use the iid recorded at target-pick time for non-triggers (see
            // PendingTarget KDoc for the multi-target-spell rationale).
            // Triggers defer to emission-time resolution via the SA id —
            // TargetingCoordinator always populates spec.forgeAbilityId when
            // spec.isTriggeredAbility=true, so that branch's fallback is
            // structurally unreachable and crashes under DevCheck.strict.
            val affectorIid =
                if (spec.affectorInstanceIdAtRecord != 0) {
                    InstanceId(spec.affectorInstanceIdAtRecord)
                } else if (spec.isTriggeredAbility) {
                    if (spec.forgeAbilityId != 0) {
                        frameIds.triggerStackAbilityIid(spec.forgeAbilityId)
                    } else {
                        DevCheck.fail {
                            "PendingTarget for ${spec.spellName} marked isTriggeredAbility but missing forgeAbilityId; " +
                                "every triggered-ability target spec must carry the SA id since stack-ability iids " +
                                "are SA-id-keyed"
                        }
                        // Emit 0 rather than the source-card-keyed iid — that
                        // would point at a non-existent stack object since
                        // ZoneMapper now mints via the SA-id-keyed surrogate.
                        // 0 surfaces visibly in invariant checks rather than
                        // routing the TargetSpec to a stale iid.
                        InstanceId(0)
                    }
                } else {
                    frameIds.cardIid(ForgeCardId(spec.spellForgeCardId))
                }
            val targetIid =
                when {
                    spec.targetForgeCardId != null ->
                        frameIds.cardIid(ForgeCardId(spec.targetForgeCardId))
                    // Player target: Arena uses seatId (1 or 2) as the iid for player entities.
                    spec.targetSeatId != null -> InstanceId(spec.targetSeatId)
                    else -> return@mapNotNull null
                }
            val grpId = GrpId(bridge.cardRepository.findGrpIdByName(spec.spellName) ?: 0)
            AnnotationBuilder.targetSpec(
                instanceId = targetIid,
                affectorId = affectorIid,
                abilityGrpId = grpId,
                index = spec.index,
                promptId = 0,
                promptParameters = affectorIid.value,
            )
        }
    }

    /** Crew annotation scan: CrewedThisTurn pAnns, ModifiedType pAnns, and expired effect annotations. */
    private fun computeCrewAnnotations(bridge: GameBridge): Triple<List<AnnotationInfo>, List<AnnotationInfo>, List<AnnotationInfo>> {
        val crewSnapshots = bridge.snapshotCrewState()
        val crewedThisTurn =
            crewSnapshots.map { snap ->
                AnnotationBuilder.crewedThisTurn(
                    InstanceId(snap.vehicleInstanceId),
                    snap.crewSourceInstanceIds.map { InstanceId(it) },
                )
            }
        val typeChange = mutableListOf<AnnotationInfo>()
        val expired = mutableListOf<AnnotationInfo>()

        val currentCrewedFids = crewSnapshots.filter { it.isCreature }.map { it.vehicleForgeCardId }.toSet()
        for (effectId in bridge.releaseCrewEffects(currentCrewedFids)) {
            expired.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }
        for (snap in crewSnapshots) {
            if (!snap.isCreature) continue
            val effectId = EffectId(bridge.getOrAllocCrewEffectId(snap.vehicleForgeCardId))
            typeChange.add(
                AnnotationBuilder.modifiedTypeLayeredEffect(
                    instanceId = InstanceId(snap.vehicleInstanceId),
                    effectId = effectId,
                    sourceAbilityGrpId = snap.crewAbilityGrpId?.let { GrpId(it) },
                ),
            )
        }
        return Triple(crewedThisTurn, typeChange, expired)
    }

    /** Saddle annotation scan: SaddledThisTurn pAnns for mounts and helper creatures. */
    private fun computeSaddleAnnotations(bridge: GameBridge): List<AnnotationInfo> =
        bridge.snapshotSaddleState().map { snap ->
            AnnotationBuilder.saddledThisTurn(
                InstanceId(snap.mountInstanceId),
                snap.saddleSourceInstanceIds.map { InstanceId(it) },
            )
        }

    /**
     * Find the active reveal across all seats, or null. Clears stale reveals where
     * proxies were synthesized but the engine skipped the choice method
     * (e.g., Duress vs all-creature hand → DiscardEffect short-circuits at max==0).
     */
    private fun detectActiveReveal(bridge: GameBridge): PromptSideEffect.RevealStarted? =
        bridge.allSeatIds().firstNotNullOfOrNull { seatId ->
            val prompt = bridge.promptBridge(SeatId(seatId))
            val reveal = prompt.journal.activeReveal() ?: return@firstNotNullOfOrNull null
            if (!bridge.revealProxies.isEmpty && prompt.getPendingPrompt() == null) {
                TargetingCoordinator.Companion.endReveal(prompt) // stale — engine skipped choice
                null
            } else {
                reveal
            }
        }

    /**
     * Synthesize RevealedCard proxy objects during active reveal-choose, or
     * schedule proxy cleanup when the reveal ends. Modifies [zones], [gameObjects],
     * and [events] in place.
     */
    // Nullable `activeReveal` is intentional: the function has two branches —
    // synthesize proxies when non-null, cleanup-and-clear when null.
    @Suppress("CanBeNonNullable")
    private fun applyRevealProxies(
        activeReveal: PromptSideEffect.RevealStarted?,
        snap: GsmSnapshot,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        events: MutableList<GameEvent>,
    ) {
        if (activeReveal != null) {
            val ownerSeat = activeReveal.ownerSeatId.value
            val viewerSeat = SeatId(ownerSeat).opponent.value
            val handZoneId = ZoneIds.handOf(ownerSeat)
            val revealedZoneId = ZoneIds.revealedOf(ownerSeat)

            val revealedZoneIdx = zones.indexOfFirst { it.zoneId == revealedZoneId }
            val revealedZoneBuilder =
                if (revealedZoneIdx >= 0) {
                    zones.removeAt(revealedZoneIdx).toBuilder()
                } else {
                    ZoneMapper.makeZone(revealedZoneId, ZoneType.Revealed, ownerSeat, Visibility.Public).toBuilder()
                }

            // Re-use proxy IDs across diffs during the same reveal (stable instanceIds).
            val needsAlloc = bridge.revealProxies.isEmpty
            for (forgeCardId in activeReveal.allHandCardIds) {
                val cardSnap = snap.objects[forgeCardId] ?: continue
                val proxyId =
                    if (needsAlloc) {
                        val id = bridge.ids.allocSynthetic()
                        bridge.revealProxies.allocate(forgeCardId, id)
                        id
                    } else {
                        bridge.revealProxies.lookup(forgeCardId) ?: continue
                    }
                revealedZoneBuilder.addObjectInstanceIds(proxyId.value)
                gameObjects.add(
                    ObjectMapper.buildRevealedCardProxy(
                        cardSnap,
                        proxyId.value,
                        handZoneId,
                        ownerSeat,
                        viewerSeat,
                        bridge.cardProto,
                        parentLinkage = snap.boundCards[forgeCardId]?.parentLinkage,
                    ),
                )
            }
            zones.add(revealedZoneBuilder.build())
        } else if (!bridge.revealProxies.isEmpty) {
            // Reveal ended — emit cleanup annotations and clear tracking.
            // Diff naturally detects missing proxy objects via snapshot-compare.
            val deletedProxies = bridge.revealProxies.drain()
            events.add(GameEvent.RevealProxiesDeleted(deletedProxies))
        }
    }

    internal fun computeAnnotations(
        events: List<GameEvent>,
        transferResult: TransferResult,
        actingSeat: Int,
        bridge: GameBridge,
        prev: GsmSnapshot? = null,
        snap: GsmSnapshot? = null,
        frameIds: FrameIdResolver? = null,
    ): AnnotationPipelineResult {
        val combatTransferredIds =
            transferResult.transfers
                .mapNotNull { transfer -> transfer.forgeCardId?.let { it to transfer.origId } }
                .toMap()
        val combatResult =
            CombatAnnotations.combatAnnotations(
                events = events,
                bridge = bridge,
                prev = prev,
                transferredIds = combatTransferredIds,
            )
        // Tests can drive computeAnnotations without a resolver; in that case
        // build a no-realloc instance from the bridge alone — `cardIid` falls
        // through to bridge.getOrAllocInstanceId, matching prior behaviour.
        val resolver = frameIds ?: FrameIdResolver(bridge)
        val (annotations, transferPersistent) =
            assembleTransferAndCombatAnnotations(
                events = events,
                transferResult = transferResult,
                actingSeat = actingSeat,
                combatResult = combatResult,
                bridge = bridge,
                snap = snap,
                frameIds = resolver,
            )
        return AnnotationPipelineResult(annotations, transferPersistent, combatResult)
    }
}
