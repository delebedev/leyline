package leyline.testkit

import leyline.game.data.CardData
import leyline.game.data.CardRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for [CardRepository] that maps any name to a stable
 * process-local grpId with name round-tripping and no card data.
 *
 * Draft and sealed generator tests exercise pack mechanics against arbitrary
 * Forge booster cards, so they need deterministic name↔grpId identity without
 * a client database or per-card fixtures. This is test-scoped data, not a
 * runtime repository mode.
 */
class SyntheticNameCardRepository : CardRepository {
    private val counter = AtomicInteger(500_000)
    private val nameToGrpId = ConcurrentHashMap<String, Int>()
    private val grpIdToName = ConcurrentHashMap<Int, String>()

    override fun findByGrpId(grpId: Int): CardData? = null

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int? =
        nameToGrpId.computeIfAbsent(name) { counter.getAndIncrement().also { grpIdToName[it] = name } }

    override fun findAllGrpIds(): List<Int> = grpIdToName.keys.toList()
}
