package leyline.game.generator

/**
 * Drives an 8-seat Forge BoosterDraft for one player. The local player picks via
 * [pick]; the 7 bot seats pick automatically inside Forge. The driver translates
 * Forge's `PaperCard` pack into Arena `grpId` lists (and back for `pick`).
 *
 * Sessions are held in-memory. Server restart drops them; see
 * [leyline.domain.service.DraftService.discardIncompleteSessions].
 *
 * Each session owns its pack state and basic-land edition, so sets may draft
 * concurrently without changing another pod's deck construction.
 */
interface BoosterDraftDriver {
    fun start(
        sessionKey: String,
        setCode: String,
    ): List<Int>

    fun pick(
        sessionKey: String,
        grpId: Int,
    ): PickResult

    fun complete(sessionKey: String): PodResult

    fun discardAll()
}

data class PickResult(
    val packNumber: Int,
    val pickNumber: Int,
    val nextPack: List<Int>,
    val complete: Boolean,
)

data class PodResult(
    val playerPool: List<Int>,
    val botDecks: List<List<Int>>,
)
