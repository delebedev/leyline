package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import leyline.DevCheck
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import leyline.game.state.TokenIdentityRegistry
import org.slf4j.LoggerFactory

/**
 * Single resolution path from a live Forge [Card] to its client grpId.
 *
 * One chain, four cases — handled exhaustively here so callers don't reimplement
 * the branches:
 *
 * 1. [GamePieceType.EFFECT] — engine-bookkeeping surrogates (Puzzle Goal, Monarch,
 *    The Ring, Radiation, City's Blessing, DetachedCardEffect, keywordEffect) have
 *    no client-DB row. Return 0; the projection layer drops them via
 *    `(gamePieceType==EFFECT && grpId==0)`.
 * 2. Token piece-typed cards (`isToken==true`):
 *    - Cached entry in [TokenIdentityRegistry] wins (stable across diff ticks).
 *    - Prepared-spell exile copy ([PreparedSpell.isCopy]) — TOKEN-piece-typed but
 *      represents a normal castable spell; resolve by current-state name.
 *    - `card.copiedPermanent != null` — copy permanent uses source's grpId.
 *    - Standard token via `tokenSpawningAbility.hostCard`'s
 *      `AbilityIdToLinkedTokenGrpId` mapping.
 * 3. Foretold cards — face-down in exile; `card.name` is empty while face-down.
 *    Resolve via the Original state's name.
 * 4. Standard non-token — `findGrpIdByName` (primary) → `findGrpIdByNameAnyFace`
 *    (DFC back faces with `IsPrimaryCard=0`).
 *
 * Token grpIds get registered in [TokenIdentityRegistry] on first resolve so the
 * chain self-stabilizes across reallocation of `Card.id` through zone moves.
 *
 * Used by [SnapshotCapture.captureCard] (every observable card, once per tick) and
 * by [GameBridge.resolveGrpId] (live-Forge consumers in the runtime path —
 * action handling that holds a Forge [Card] without a [CardSnapshot] in scope).
 */
object GrpIdResolver {
    private val log = LoggerFactory.getLogger(GrpIdResolver::class.java)

    /**
     * Names already reported as missing from the client card DB. Snapshots run
     * 100s of times per game and `card.name` is stable across them, so without
     * dedup a single missing card emits an error per tick (observed: 1631
     * errors / 71s game when a Forge-only card name is in the deck).
     *
     * **Scope: JVM-static (per resolver-singleton lifetime).** In a multi-game
     * batch (e.g. simclient batch run) the same missing card name only logs
     * ERROR once across the whole batch — downstream telemetry that counts
     * per-game ERROR events will under-report missing cards in games 2..N.
     * This is intentional: a missing card is a deck/DB drift problem, not a
     * per-game one, and a single ERROR suffices to surface it.
     *
     * Tests that need fresh dedup state across runs can call
     * [resetReportedMissingCardNamesForTest].
     *
     * Soft cap [MAX_REPORTED_MISSING] bounds memory if a runaway deck contains
     * many missing names; further misses past the cap go unreported.
     */
    private val reportedMissingCardNames: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()
    private const val MAX_REPORTED_MISSING = 256

    /** Test-only: clear the dedup set so a new batch starts fresh. */
    @Suppress("unused")
    fun resetReportedMissingCardNamesForTest() {
        reportedMissingCardNames.clear()
    }

    private fun reportMissingOnce(
        kind: String,
        name: String,
        forgeId: Int,
    ) {
        if (reportedMissingCardNames.size >= MAX_REPORTED_MISSING) return
        if (reportedMissingCardNames.add(name)) {
            log.error("$kind grpId=0 for card '{}' (forgeId={}): not in client card DB", name, forgeId)
        }
    }

    /**
     * Resolve grpId for [card]. See class KDoc for the chain.
     *
     * @param instanceId client instanceId for [TokenIdentityRegistry] cache lookups.
     *   `0` skips the cache (use when no instance has been allocated yet).
     */
    @Suppress(
        // Each branch is a distinct case in the chain (EFFECT, token cache, prepared
        // copy, copy permanent, standard token, foretold, regular) — collapsing
        // them into a single tail expression would lose the registry-write side
        // effects per branch.
        "ReturnCount",
    )
    fun resolve(
        card: Card,
        cards: CardRepository,
        instanceId: Int = 0,
        tokenRegistry: TokenIdentityRegistry = TokenIdentityRegistry(),
    ): Int {
        if (card.gamePieceType == GamePieceType.EFFECT) return 0

        if (card.isToken) {
            // 1. Registry cache — stable across diff ticks
            tokenRegistry.resolve(instanceId)?.let { return it }

            // 2. Prepared-spell copy — Forge marks the alt face copy as TOKEN-piece
            // typed, but it represents a normal castable spell. Resolve by name on
            // the current face, which survives the Forge `Card.id` reallocation
            // that happens when the copy moves Exile → Stack on cast.
            //
            // This branch must run BEFORE the token-spawning-ability path
            // (a prepared copy is `isToken==true` but doesn't have a
            // `tokenSpawningAbility.hostCard`, so the standard token resolution would
            // fall through to `DevCheck.fail`).
            PreparedSpell.resolveCopyGrpId(card, cards)?.let { preparedGrpId ->
                if (instanceId != 0) tokenRegistry.register(instanceId, preparedGrpId)
                return preparedGrpId
            }

            // 3. Copy token — use source permanent's grpId
            val copiedPermanent = card.copiedPermanent
            if (copiedPermanent != null) {
                val sourceGrpId =
                    cards.findGrpIdByNameAnyFace(copiedPermanent.name)
                        ?: run {
                            reportMissingOnce("copy token", copiedPermanent.name, card.id)
                            return GameBridge.FALLBACK_GRPID
                        }
                if (instanceId != 0) tokenRegistry.register(instanceId, sourceGrpId)
                return sourceGrpId
            }

            // 4. Standard token — AbilityIdToLinkedTokenGrpId lookup
            val tokenGrpId = resolveTokenGrpId(card, cards)
            if (tokenGrpId != null) {
                if (instanceId != 0) tokenRegistry.register(instanceId, tokenGrpId)
                return tokenGrpId
            }
            reportMissingOnce("token", card.name, card.id)
            DevCheck.fail { "token grpId=0 for '${card.name}' (forgeId=${card.id})" }
            return GameBridge.FALLBACK_GRPID
        }

        // Foretold cards are face-down in exile — Forge's `card.name` is "" while
        // face-down, which would crash the strict resolver. Look up via the
        // Original state's name (the underlying card identity) instead.
        if (Foretell.isForetold(card)) {
            val originalName =
                card.getOriginalState(forge.card.CardStateName.Original)?.name ?: card.name
            return cards.findGrpIdByName(originalName)
                ?: cards.findGrpIdByNameAnyFace(originalName)
                ?: GameBridge.FALLBACK_GRPID
        }

        // Rooms (split enchantments with two doors) carry the parent grpId
        // everywhere except on the stack — Forge's active state flips to
        // LeftSplit / RightSplit when a door unlocks, but the projected card
        // identity must remain the parent (Original) so the client renders the
        // full room with both door names + abilities. Look up via Original
        // state's name when off-stack; let the stack branch fall through to
        // the active-state name for the per-door face grpId.
        if (card.isRoom && !card.isInZone(forge.game.zone.ZoneType.Stack)) {
            val originalName =
                card.getOriginalState(forge.card.CardStateName.Original)?.name?.takeIf { it.isNotEmpty() }
                    ?: card.name
            cards.findGrpIdByName(originalName)?.let { return it }
            cards.findGrpIdByNameAnyFace(originalName)?.let { return it }
        }

        // Primary-face lookup, falling back to any-face for DFC back faces
        // (e.g. saga transforms to Echo of Death's Wail — the back face lives in
        // the Arena DB under a non-primary flag; findGrpIdByName misses it).
        return cards.findGrpIdByName(card.name)
            ?: cards.findGrpIdByNameAnyFace(card.name)
            ?: run {
                reportMissingOnce("standard", card.name, card.id)
                DevCheck.fail { "grpId=0 for '${card.name}' (forgeId=${card.id}): not in client card DB" }
                GameBridge.FALLBACK_GRPID
            }
    }

    /** Resolve token grpId via source card's AbilityIdToLinkedTokenGrpId mapping. */
    private fun resolveTokenGrpId(
        card: Card,
        cards: CardRepository,
    ): Int? {
        val sourceCard = card.tokenSpawningAbility?.hostCard ?: return null
        // Try current state name first (e.g. "Pest Problem" for adventure on stack),
        // then primary face name as fallback. Token mappings in Arena DB can be on
        // either face — adventure tokens map from the adventure face grpId.
        val sourceGrpId =
            cards.findGrpIdByNameAnyFace(sourceCard.name)
                ?: return null
        return cards.tokenGrpIdForCard(sourceGrpId, card.name)
    }
}
