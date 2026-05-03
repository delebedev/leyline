package leyline.game.generator

import forge.deck.DeckSection
import forge.item.PaperCard
import forge.model.FModel
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.CardRepository
import org.slf4j.LoggerFactory

/**
 * [BoosterDraftDriver] backed by Forge's `BoosterDraft` engine.
 *
 * Holds one [HeadlessBoosterDraft] per active session in memory. Translates
 * between Forge `PaperCard` and Arena `grpId` via [CardRepository].
 *
 * Falls back to FDN when [start] is given a set with no Forge booster template
 * (matches the pre-existing `DraftPackGenerator` behaviour so QuickDraft keeps
 * working for sets we haven't templated yet).
 */
class ForgeBoosterDraftDriver(
    private val cards: CardRepository,
) : BoosterDraftDriver {
    private val log = LoggerFactory.getLogger(ForgeBoosterDraftDriver::class.java)

    private data class Active(
        val draft: HeadlessBoosterDraft,
        var packIndex: Int,
        var pickIndex: Int,
    )

    private val sessions = mutableMapOf<String, Active>()

    override fun start(
        sessionKey: String,
        setCode: String,
    ): List<Int> {
        GameBootstrap.initializeCardDatabase()
        check(sessionKey !in sessions) { "Draft session $sessionKey already started" }

        val effectiveSet = resolveSet(setCode)
        val draft = HeadlessBoosterDraft(effectiveSet)
        sessions[sessionKey] = Active(draft, packIndex = 0, pickIndex = 0)
        return packToGrpIds(draft.currentPackPaperCards())
    }

    override fun pick(
        sessionKey: String,
        grpId: Int,
    ): PickResult {
        val active = sessions[sessionKey] ?: error("No active draft session: $sessionKey")
        val draft = active.draft

        val pack = draft.currentPackPaperCards()
        val card =
            pack.firstOrNull { cards.findGrpIdByName(it.name) == grpId }
                ?: error("Card grpId=$grpId not in current pack (session=$sessionKey)")

        val accepted = draft.chooseLocally(card)
        check(accepted) { "Forge rejected pick: card=${card.name} session=$sessionKey" }

        val complete = !draft.hasNextChoice()
        val nextPack = if (complete) emptyList() else draft.currentPackPaperCards()
        // Forge's `getRound()` is 1-indexed (incremented in startRound() on each pack
        // rotation). The QuickDraft client uses a 0-indexed packNumber.
        val nextPackIndex = if (complete) active.packIndex else (draft.round - 1)

        val nextPickIndex =
            if (complete) {
                active.pickIndex + 1
            } else if (nextPackIndex == active.packIndex) {
                active.pickIndex + 1
            } else {
                0
            }

        active.packIndex = nextPackIndex
        active.pickIndex = nextPickIndex

        return PickResult(
            packNumber = nextPackIndex,
            pickNumber = nextPickIndex,
            nextPack = packToGrpIds(nextPack),
            complete = complete,
        )
    }

    override fun complete(sessionKey: String): PodResult {
        val active = sessions[sessionKey] ?: error("No active draft session: $sessionKey")
        val draft = active.draft
        val playerPool = packToGrpIds(draft.localPlayerPool())
        val botDecks =
            draft.computerDeckMains().map { deck ->
                val main = deck.getOrCreate(DeckSection.Main)
                val grpIds = mutableListOf<Int>()
                for (entry in main) {
                    val grpId = cards.findGrpIdByName(entry.key.name)
                    if (grpId != null) {
                        repeat(entry.value) { grpIds.add(grpId) }
                    } else {
                        log.warn("Bot deck: no grpId for '{}'", entry.key.name)
                    }
                }
                grpIds.toList()
            }
        sessions.remove(sessionKey)
        return PodResult(playerPool = playerPool, botDecks = botDecks)
    }

    override fun discardAll() {
        sessions.clear()
    }

    private fun packToGrpIds(pack: List<PaperCard>): List<Int> {
        val out = mutableListOf<Int>()
        for (card in pack) {
            val grpId = cards.findGrpIdByName(card.name)
            if (grpId != null) {
                out.add(grpId)
            } else {
                log.warn("Draft pack: no grpId for '{}'", card.name)
            }
        }
        return out
    }

    private fun resolveSet(setCode: String): String {
        val boosters = FModel.getMagicDb().getBoosters()
        if (boosters.get(setCode) != null) return setCode
        log.warn("No booster template for '{}', falling back to FDN", setCode)
        return "FDN"
    }
}
