package leyline.domain

/**
 * Draft session state — tracks pick-by-pick progress through 3 packs.
 *
 * Quick Draft lifecycle: `PickNext -> ... -> Completed`.
 * Pack contents come from a `BoosterDraftDriver` one pack at a time; the session
 * only carries the pack the player is currently choosing from. The driver
 * internally drives 7 bot picks between human picks.
 *
 * BotDraft responses are Course-wrapped double-encoded JSON:
 * `{"CurrentModule":"BotDraft","Payload":"{\"Result\":\"Success\",...}"}`.
 * The Payload is a JSON string containing the draft state fields.
 */

@JvmInline value class DraftSessionId(
    val value: String,
)

enum class DraftStatus {
    PickNext,
    Completed,
    ;

    fun wireName(): String = name
}

data class DraftSession(
    val id: DraftSessionId,
    val playerId: PlayerId,
    val eventName: String,
    val status: DraftStatus = DraftStatus.PickNext,
    val packNumber: Int = 0,
    val pickNumber: Int = 0,
    /** Cards available in the current pack for picking. */
    val draftPack: List<Int> = emptyList(),
    /** Cards picked so far (cumulative). */
    val pickedCards: List<Int> = emptyList(),
)
