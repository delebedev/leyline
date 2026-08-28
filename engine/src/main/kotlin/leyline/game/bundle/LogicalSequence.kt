package leyline.game.bundle

import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** Logical GRE identity and output order committed with the match projection. */
data class LogicalSequenceState(
    val currentGsId: Int = 0,
    val currentMsgId: Int = 1,
    val lastPromptGsId: Int = 0,
    val lastPromptMsgId: Int = 0,
    val lastGameStateGsId: Int = 0,
    val committedOutputOrdinal: Long = 0,
)

/**
 * Private mutable planner for one tentative cut.
 *
 * Production code forks it from committed [LogicalSequenceState] and discards it
 * unless the surrounding projection transition installs. Direct materializer tests
 * may construct one as a standalone planner.
 */
class LogicalSequencePlanner(
    initial: LogicalSequenceState = LogicalSequenceState(),
) {
    constructor(
        initialGsId: Int = 0,
        initialMsgId: Int = 1,
    ) : this(LogicalSequenceState(currentGsId = initialGsId, currentMsgId = initialMsgId))

    data class GameStateLink(
        val gsId: Int,
        val prevGsId: Int,
    )

    private var state = initial

    fun nextGsId(): Int = (state.currentGsId + 1).also { state = state.copy(currentGsId = it) }

    fun nextGameStateLink(): GameStateLink {
        val next = nextGsId()
        val previous = state.lastGameStateGsId.takeIf { it in 1 until next } ?: (next - 1).coerceAtLeast(0)
        return GameStateLink(next, previous)
    }

    fun nextMsgId(): Int = (state.currentMsgId + 1).also { state = state.copy(currentMsgId = it) }

    fun currentGsId(): Int = state.currentGsId

    fun currentMsgId(): Int = state.currentMsgId

    fun lastGameStateGsId(): Int = state.lastGameStateGsId

    fun setGsId(value: Int) {
        state = state.copy(currentGsId = value)
    }

    fun setMsgId(value: Int) {
        state = state.copy(currentMsgId = value)
    }

    fun observe(message: GREToClientMessage) {
        if (message.hasGameStateMessage()) {
            state = state.copy(lastGameStateGsId = maxOf(state.lastGameStateGsId, message.gameStateMessage.gameStateId))
        }
        if (message.type in PROMPT_GRE_TYPES) {
            state =
                state.copy(
                    lastPromptGsId = maxOf(state.lastPromptGsId, message.gameStateId),
                    lastPromptMsgId = maxOf(state.lastPromptMsgId, message.msgId),
                )
        }
    }

    fun observe(messages: Iterable<GREToClientMessage>) = messages.forEach(::observe)

    fun allocateOutputOrdinal(): Long = (state.committedOutputOrdinal + 1).also { state = state.copy(committedOutputOrdinal = it) }

    fun snapshot(): LogicalSequenceState = state
}
