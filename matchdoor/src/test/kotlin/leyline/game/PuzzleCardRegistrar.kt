package leyline.game

import forge.game.card.Card
import leyline.conformance.FixtureCardLoader
import leyline.game.data.CardRepository
import org.slf4j.LoggerFactory

/**
 * Registers puzzle cards in an [InMemoryCardRepository] at runtime.
 *
 * Routes through [FixtureCardLoader]: Arena identity comes from YAML fixtures
 * under `matchdoor/src/test/resources/test-cards/`; rules data (P/T, types,
 * mana, etc.) is derived from Forge's `CardRules`. The fixture's
 * `linkedFaces` and `tokens` lists drive closure resolution — alternate
 * faces and produced tokens are auto-registered without walking Forge
 * states explicitly.
 *
 * The [clientRepo] parameter is retained for source-compat with existing
 * call sites and ignored; fixtures are now the single source of truth for
 * grpIds.
 */
class PuzzleCardRegistrar(
    private val repo: InMemoryCardRepository,
    @Suppress("UNUSED_PARAMETER", "UnusedPrivateProperty")
    private val clientRepo: CardRepository? = null,
) {
    @Suppress("UnusedPrivateProperty")
    private val log = LoggerFactory.getLogger(PuzzleCardRegistrar::class.java)

    /**
     * Ensure a card (and its closure: linked faces, produced tokens) is
     * registered in the repository. Idempotent. Errors loudly when the
     * card has no fixture and Forge knows the name (likely a real card
     * missing a fixture); returns 0 silently for engine-internal names.
     */
    fun ensureCardRegistered(card: Card): Int = FixtureCardLoader.ensureCardRegistered(repo, card.name)

    /** Same as [ensureCardRegistered] but takes a name directly. */
    fun ensureCardRegisteredByName(cardName: String): Int = FixtureCardLoader.ensureCardRegistered(repo, cardName)
}
