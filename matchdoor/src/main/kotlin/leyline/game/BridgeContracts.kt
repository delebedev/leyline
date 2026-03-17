package leyline.game

import forge.game.Game
import forge.game.player.Player
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo

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
    fun recordZone(instanceId: InstanceId, zoneId: Int): Int?

    /** Get the zone an instanceId was last seen in. */
    fun getPreviousZone(instanceId: InstanceId): Int?

    /** Retire an instanceId to the Limbo zone (protocol-only, no Forge equivalent). */
    fun retireToLimbo(instanceId: InstanceId)

    /** All retired instanceIds in order. */
    fun getLimboInstanceIds(): List<InstanceId>
}

/** Diff baselines + client-seen turn tracking. */
interface StateSnapshot {
    /** Store a full GSM snapshot as the next diff baseline. */
    fun snapshotDiffBaseline(state: GameStateMessage)

    /** Current diff baseline (null before first state). */
    fun getDiffBaselineState(): GameStateMessage?

    /** Record TurnInfo from a GSM the client actually received. */
    fun recordClientSeenTurnInfo(gsm: GameStateMessage)

    /**
     * True if [currentTurnInfo] represents a phase/step change from the last
     * state sent to the client. Also true when no prior state has been sent.
     */
    fun isPhaseChangedFromClientSeen(currentTurnInfo: TurnInfo): Boolean
}

/** Monotonic annotation ID counters. */
interface AnnotationIds {
    /** Allocate the next sequential annotation ID. */
    fun nextAnnotationId(): Int

    /** Allocate the next sequential persistent annotation ID. */
    fun nextPersistentAnnotationId(): Int
}

/** Drain queued game events for annotation building. */
interface EventDrain {
    /** Drain all queued events since last drain. Empty if no events. */
    fun drainEvents(): List<GameEvent>
}
