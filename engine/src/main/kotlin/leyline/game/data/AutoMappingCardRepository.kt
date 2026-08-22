package leyline.game.data

import leyline.game.InMemoryCardRepository
import leyline.tooling.headless.FixtureCardLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Card repository for CI and lightweight web runs without a local card DB.
 *
 * Names backed by checked-in fixtures resolve to fixture grpIds and CardData.
 * Other names receive stable process-local grpIds on demand, enough for draft
 * pack/pick/deck flows that only need name round-tripping.
 */
class AutoMappingCardRepository(
    private val useFixtures: Boolean = false,
    private val fixtureRepo: InMemoryCardRepository? = if (useFixtures) InMemoryCardRepository() else null,
) : CardRepository {
    init {
        require(useFixtures || fixtureRepo == null) { "fixtureRepo requires useFixtures=true" }
    }

    private val counter = AtomicInteger(500_000)
    private val nameToGrpId = ConcurrentHashMap<String, Int>()
    private val grpIdToName = ConcurrentHashMap<Int, String>()

    override fun findByGrpId(grpId: Int): CardData? = fixtureRepo?.findByGrpId(grpId)

    override fun findNameByGrpId(grpId: Int): String? = fixtureRepo?.findNameByGrpId(grpId) ?: grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int =
        fixtureGrpIdByName(name) ?: nameToGrpId.computeIfAbsent(name) { counter.getAndIncrement().also { grpIdToName[it] = name } }

    override fun findGrpIdByNameAnyFace(name: String): Int? = findGrpIdByName(name)

    override fun findTokenGrpIdByName(name: String): Int? = fixtureRepo?.findTokenGrpIdByName(name)

    override fun findAllGrpIds(): List<Int> =
        buildList {
            fixtureRepo?.findAllGrpIds()?.let(::addAll)
            addAll(nameToGrpId.values)
        }.distinct()

    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = fixtureRepo?.findAbilityInfo(abilityGrpId)

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = fixtureRepo?.lookupModalOptions(cardGrpId)

    private fun fixtureGrpIdByName(name: String): Int? {
        val repo = fixtureRepo ?: return null
        if (TestCardFixtures.findFixture(name) == null) return null
        val grpId = FixtureCardLoader.ensureCardRegistered(repo, name).takeIf { it != 0 } ?: return null
        grpIdToName[grpId] = name
        return grpId
    }
}
