package leyline.game.state

import forge.game.Game
import forge.game.player.Player
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.GamePlayback
import leyline.game.event.FrameEventLog

/**
 * Focused interfaces for [GameBridge] capabilities.
 *
 * StateMapper (and future sub-mappers) depend on these contracts rather than
 * the full GameBridge. Each sub-mapper declares only the interfaces it needs,
 * enabling unit tests with minimal stubs.
 *
 * [GameBridge] implements all five interfaces.
 */

/** Forge cardId ↔ client instanceId translation. Used by nearly every mapper. */
interface IdMapping {
    /** Allocate or return existing client instanceId for a Forge card ID. */
    fun getOrAllocInstanceId(forgeCardId: ForgeCardId): InstanceId

    /** Allocate a fresh instanceId for a card that changed zones. */
    fun reallocInstanceId(forgeCardId: ForgeCardId): InstanceIdRegistry.IdReallocation

    /** Reverse lookup: client instanceId → Forge card ID. */
    fun getForgeCardId(instanceId: InstanceId): ForgeCardId?
}

/** Seat number → Forge Player/Game resolution. Used by nearly every mapper. */
interface PlayerLookup {
    /** Map seat ID (1=human, 2=AI) to Forge player. */
    fun getPlayer(seatId: SeatId): Player?

    /** The underlying Forge game (null before start). */
    fun getGame(): Game?
}

/** Zone membership tracking for diff computation and zone-transfer detection. */
interface ZoneTracking {
    /** Record current zone for an instance. Returns previous zone or null if new. */
    fun recordZone(
        instanceId: InstanceId,
        zoneId: Int,
    ): Int?

    /** Get the zone an instanceId was last seen in. */
    fun getPreviousZone(instanceId: InstanceId): Int?

    /** Retire an instanceId to the Limbo zone (protocol-only, no Forge equivalent). */
    fun retireToLimbo(instanceId: InstanceId)

    /** All retired instanceIds in order. */
    fun getLimboInstanceIds(): List<InstanceId>
}

/** Monotonic annotation ID counters. */
interface AnnotationIds {
    /** Allocate the next sequential annotation ID. */
    fun nextAnnotationId(): Int

    /** Allocate the next sequential persistent annotation ID. */
    fun nextPersistentAnnotationId(): Int
}

/** Close the current event frame and return its accumulated events. */
interface EventDrain {
    /** Atomically swap the open frame for an empty one and return the closed log. */
    fun closeFrame(): FrameEventLog
}

/**
 * Read-only view of [GameBridge] for [leyline.match.AutoPassEngine].
 *
 * Extends [PlayerLookup] (getGame, getPlayer) and adds the priority/playback
 * surface needed by the auto-pass loop. Extracted so AutoPassEngine can be
 * unit-tested with a stub instead of a full GameBridge + Forge engine.
 */
interface AutoPassView : PlayerLookup {
    /** Per-seat action playback queue, when this seat has one. */
    fun playbackFor(seatId: SeatId): GamePlayback?

    /** Seat-scoped bridge facade (action + prompt + mulligan bridges). */
    fun seat(seatId: SeatId): GameBridge.SeatBridges

    /** Block until the engine reaches a priority stop. */
    fun awaitPriority()

    /** Block until priority with timeout. Returns false on timeout/game-over. */
    fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean
}
