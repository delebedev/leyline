package leyline.match

import kotlinx.serialization.Serializable

/**
 * Types of game events tracked for debug/tracing purposes.
 *
 * Defined in match/ so the match module can reference them without
 * depending on the debug infrastructure. [GameStateCollector][leyline.debug.GameStateCollector]
 * uses these values when recording events.
 */
@Serializable
enum class MatchEventType {
    PRIORITY_GRANT,
    AUTO_PASS,
    SEND_STATE,
    CLIENT_ACTION,
    COMBAT_PROMPT,
    TARGET_PROMPT,
    GAME_OVER,
    GAME_START,
    AI_TURN_WAIT,
    AI_TURN_TIMEOUT,
}
