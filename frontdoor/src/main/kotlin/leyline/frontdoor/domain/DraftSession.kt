package leyline.frontdoor.domain

/**
 * Draft session state — tracks pick-by-pick progress through 3 packs.
 *
 * Quick Draft lifecycle: `PickNext -> ... -> Completed`.
 * Each pick removes a card from [draftPack] and adds it to [pickedCards].
 * When all 39 picks are made (3 packs x 13 picks), status becomes Completed.
 *
 * **Wire format:** BotDraft responses are Course-wrapped double-encoded JSON:
 * `{"CurrentModule":"BotDraft","Payload":"{\"Result\":\"Success\",...}"}`
 * The Payload is a JSON string containing the draft state fields.
 */

@JvmInline value class DraftSessionId(val value: String)

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
    /** All packs for the draft session (3 packs, pre-generated). */
    val packs: List<List<Int>> = emptyList(),
    /** Cards picked so far (cumulative). */
    val pickedCards: List<Int> = emptyList(),
)
