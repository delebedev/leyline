package leyline.testkit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic name↔grpId mapping for generator tests.
 *
 * Draft and sealed generator tests exercise pack mechanics against arbitrary
 * Forge booster cards, so they need a deterministic name↔grpId identity
 * without a client database or per-card fixtures. This is test-scoped data,
 * not a runtime repository mode.
 */
class SyntheticNameLookup {
    private val counter = AtomicInteger(500_000)
    private val nameToGrpId = ConcurrentHashMap<String, Int>()
    private val grpIdToName = ConcurrentHashMap<Int, String>()

    fun findGrpIdByName(name: String): Int? =
        nameToGrpId.computeIfAbsent(name) { counter.getAndIncrement().also { grpIdToName[it] = name } }

    fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]
}
