package leyline.game.generator

import forge.deck.Deck
import forge.deck.DeckSection
import forge.gamemodes.limited.BoosterDraft
import forge.gamemodes.limited.IBoosterDraft
import forge.gamemodes.limited.LimitedPoolType
import forge.item.PaperCard
import forge.item.generation.UnOpenedProduct
import forge.model.FModel

/**
 * BoosterDraft variant that constructs without going through Forge's GUI-coupled
 * `createDraft(...)` factories (which call `SGuiChoose` for set/block selection).
 *
 * Builds three identical packs from the booster template registered for [setCode]
 * and seeds the land set so `BoosterDraftAI.buildDeck()` has lands to add.
 */
class HeadlessBoosterDraft(
    setCode: String,
) : BoosterDraft(LimitedPoolType.Full, POD_SIZE) {
    init {
        val booster =
            FModel.getMagicDb().getBoosters().get(setCode)
                ?: error("No booster template for set: $setCode")
        val supplier = UnOpenedProduct(booster)
        repeat(PACK_COUNT) { product.add(supplier) }
        IBoosterDraft.LAND_SET_CODE[0] = FModel.getMagicDb().getEditions().get(setCode)
        initializeBoosters()
    }

    /** Card list currently offered to the local (seat 0) player; empty when no pack to choose. */
    fun currentPackPaperCards(): List<PaperCard> = nextChoice()?.toFlatList() ?: emptyList()

    /** Final 7 bot decks (seat 1..7). Computer-built once at draft completion. */
    fun computerDeckMains(): List<Deck> = getComputerDecks().toList()

    /** Local player pool — every card the human chose, in pick order. */
    fun localPlayerPool(): List<PaperCard> = humanPlayer.deck.getOrCreate(DeckSection.Sideboard).toFlatList()

    fun chooseLocally(card: PaperCard): Boolean = setChoice(card, DeckSection.Sideboard)

    companion object {
        const val POD_SIZE = 8
        const val PACK_COUNT = 3
    }
}
