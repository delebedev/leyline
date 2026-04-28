package leyline.conformance

import forge.game.card.Card
import forge.model.FModel
import leyline.game.InMemoryCardRepository
import leyline.game.data.TestCardFixtures
import org.slf4j.LoggerFactory

/**
 * Joining layer between [TestCardFixtures] (Arena identity) and Forge
 * (rules data). The single entry point that test setup paths use to
 * register a card by name into an [InMemoryCardRepository].
 *
 * Source of truth for grpId, ability ids, token map, linked faces is the
 * YAML fixture under `matchdoor/src/test/resources/test-cards/`. For Slim
 * fixtures, rules data (P/T, types, mana cost, etc.) is derived from
 * Forge's `CardRules`. For Full fixtures (tokens, Alchemy), data is
 * self-contained in YAML.
 *
 * Errors loudly when a fixture is missing — no synthetic-grpId fallback.
 * If a test needs a card without a fixture, generate one with the
 * `card-fixtures emit "<Card Name>"` tool.
 */
object FixtureCardLoader {
    @Suppress("UnusedPrivateProperty")
    private val log = LoggerFactory.getLogger(FixtureCardLoader::class.java)

    /**
     * Register [cardName] (and its closure: linked faces, produced tokens)
     * into [repo]. Idempotent — if [cardName] already resolves in [repo],
     * returns its grpId without re-registering.
     */
    fun ensureCardRegistered(repo: InMemoryCardRepository, cardName: String): Int {
        repo.findGrpIdByName(cardName)?.let { return it }

        val closure = TestCardFixtures.findClosure(cardName)
        if (closure.isEmpty()) {
            // No fixture. If Forge has no entry either, treat as engine-internal
            // (Puzzle Goal, DetachedCardEffect, etc.) — return 0 quietly. If
            // Forge knows the name, the card is real and a fixture is missing.
            if (forgeHas(cardName)) {
                error(
                    "No fixture for '$cardName' under matchdoor/src/test/resources/test-cards/. " +
                        "Generate via `card-fixtures emit \"$cardName\"`.",
                )
            }
            log.debug("Skipping '{}' — not in fixtures or Forge (engine-internal)", cardName)
            return 0
        }

        for (f in closure) {
            // Skip if a closure entry has already been registered (e.g. shared
            // tokens across saga chapters, or token-producer + token registered
            // separately by sibling tests).
            if (repo.findByGrpId(f.identity.grpId) != null) continue
            when (f) {
                is TestCardFixtures.Fixture.Full -> TestCardFixtures.applyFull(repo, f)
                is TestCardFixtures.Fixture.Slim -> registerSlim(repo, f)
            }
        }

        return repo.findGrpIdByName(cardName)
            ?: error("Closure for '$cardName' resolved but did not register the named card itself")
    }

    private fun forgeHas(cardName: String): Boolean {
        val db = FModel.getMagicDb()?.commonCards ?: return false
        return db.getCard(cardName) != null ||
            run {
                forge.StaticData.instance().attemptToLoadCard(cardName)
                db.getCard(cardName) != null
            }
    }

    private fun registerSlim(
        repo: InMemoryCardRepository,
        fixture: TestCardFixtures.Fixture.Slim,
    ) {
        val forgeCard = loadForgeCard(fixture.identity.name)
        val data = CardDataDeriver.fromForgeCardWithIdentity(forgeCard, fixture.identity)
        repo.registerData(data, fixture.identity.name)
        TestCardFixtures.registerAbilityMetadata(repo, fixture.identity)
        registerForgeKeywordMap(repo, forgeCard, fixture.identity)
    }

    /**
     * Populate the test-only `keywordMaps` for keyword names Forge knows but
     * Arena's `Abilities.BaseId` doesn't identify (PROWESS, MADNESS, etc.).
     * Maps Forge's keyword list to the leading positions of the fixture's
     * ability ids — the same assumption the pre-migration `AbilityIdDeriver`
     * used. `findKeywordAbilityGrpId` keeps the BaseId path; this is the
     * fallback consulted by `findTestKeywordAbilityGrpId` for keywords with
     * no BaseId entry.
     */
    private fun registerForgeKeywordMap(
        repo: InMemoryCardRepository,
        forgeCard: forge.game.card.Card,
        identity: TestCardFixtures.Identity,
    ) {
        val keywords = forgeCard.rules?.mainPart?.keywords?.toList() ?: return
        if (keywords.isEmpty() || identity.abilities.isEmpty()) return
        val map = mutableMapOf<String, Int>()
        for ((i, kw) in keywords.withIndex()) {
            if (i >= identity.abilities.size) break
            // Forge keyword strings can have argument forms like "PROWESS:foo" or
            // "WARP:1 G". InMemoryCardRepository's keyword resolver matches on
            // uppercase prefix, so the raw form is fine.
            map[kw.uppercase()] = identity.abilities[i].id
        }
        if (map.isNotEmpty()) repo.registerKeywordAbilityGrpIds(identity.grpId, map)
    }

    private fun loadForgeCard(cardName: String): Card {
        val db = FModel.getMagicDb()?.commonCards
            ?: error("Forge card DB not initialized — call GameBootstrap.initializeCardDatabase first")
        val paperCard = db.getCard(cardName)
            ?: run {
                forge.StaticData.instance().attemptToLoadCard(cardName)
                db.getCard(cardName)
            }
            ?: error(
                "Slim fixture for '$cardName' but Forge has no entry. " +
                    "Either the fixture should be Full (regenerate with `card-fixtures emit`) " +
                    "or the card name has drifted between Arena and Forge.",
            )
        return Card.fromPaperCard(paperCard, null)
    }
}
