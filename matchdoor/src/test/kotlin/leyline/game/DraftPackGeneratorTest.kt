package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.generator.DraftPackGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Auto-assigns a synthetic grpId for every card name it sees.
 * Lets us test pack generation without the client card DB.
 */
private class AutoMappingCardRepository : CardRepository {
    private val counter = AtomicInteger(500_000)
    private val nameToGrpId = mutableMapOf<String, Int>()
    private val grpIdToName = mutableMapOf<Int, String>()

    override fun findGrpIdByName(name: String): Int =
        nameToGrpId.getOrPut(name) { counter.getAndIncrement().also { grpIdToName[it] = name } }

    override fun findByGrpId(grpId: Int): CardData? = null

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findAllGrpIds(): List<Int> = nameToGrpId.values.toList()
}

class DraftPackGeneratorTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("generates 3 non-empty packs") {
            val packs = DraftPackGenerator(AutoMappingCardRepository()).generate("FDN")
            packs shouldHaveSize 3
            packs.forEach { it.size shouldBeGreaterThan 0 }
        }

        test("packs are distinct") {
            val packs = DraftPackGenerator(AutoMappingCardRepository()).generate("FDN")
            val uniquePacks = packs.map { it.sorted() }.toSet()
            uniquePacks.size shouldNotBe 1
        }

        test("unknown set falls back to FDN") {
            val packs = DraftPackGenerator(AutoMappingCardRepository()).generate("ZZZZZ")
            packs shouldHaveSize 3
            packs.forEach { it.size shouldBeGreaterThan 0 }
        }
    })
