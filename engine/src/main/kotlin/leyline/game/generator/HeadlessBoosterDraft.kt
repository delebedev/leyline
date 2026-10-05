package leyline.game.generator

import forge.deck.Deck
import forge.deck.DeckSection
import forge.gamemodes.limited.BoosterDraft
import forge.gamemodes.limited.DefaultDraftPickStrategy
import forge.gamemodes.limited.DraftPickStrategy
import forge.gamemodes.limited.LimitedPlayerAI
import forge.gamemodes.limited.LimitedPoolType
import forge.item.PaperCard
import forge.item.generation.UnOpenedProduct
import forge.model.FModel

/**
 * BoosterDraft variant that constructs without going through Forge's GUI-coupled
 * `createDraft(...)` factories (which call `SGuiChoose` for set/block selection).
 *
 * Builds three identical packs from the booster template registered for [setCode]
 * and retains that set's edition for bot basic-land selection.
 */
class HeadlessBoosterDraft(
    private val setCode: String,
    draftPickStrategy: DraftPickStrategy = DefaultDraftPickStrategy(),
) : BoosterDraft(LimitedPoolType.Full, POD_SIZE, draftPickStrategy) {
    init {
        val booster =
            FModel.getMagicDb().getBoosters().get(setCode)
                ?: error("No booster template for set: $setCode")
        val supplier = UnOpenedProduct(booster)
        repeat(PACK_COUNT) { product.add(supplier) }
        initializeBoosters()
    }

    /** Card list currently offered to the local (seat 0) player; empty when no pack to choose. */
    fun currentPackPaperCards(): List<PaperCard> = nextChoice()?.toFlatList() ?: emptyList()

    // Every pod supplies its own basic-land edition to deck construction.
    override fun getComputerDecks(): Array<Deck> = opposingPlayers.map { (it as LimitedPlayerAI).buildDeck(setCode) }.toTypedArray()

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
