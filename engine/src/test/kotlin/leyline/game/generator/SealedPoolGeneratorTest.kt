package leyline.game.generator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.AutoMappingCardRepository
import leyline.game.data.CardRepository

/** Wraps a real repository but reports every name lookup as unmapped, to exercise miss-handling. */
private class AlwaysMissingSealedCardRepository(
    delegate: CardRepository = AutoMappingCardRepository(),
) : CardRepository by delegate {
    override fun findGrpIdByName(name: String): Int? = null
}

class SealedPoolGeneratorTest :
    FunSpec({

        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("generates a 6-pack pool with a mapped grpId per card") {
            val pool = SealedPoolGenerator(AutoMappingCardRepository()).generate("FDN")

            pool.grpIds.shouldNotBeEmpty()
            pool.collationId shouldBe 100026
        }

        test("throws when a card name has no grpId mapping") {
            val generator = SealedPoolGenerator(AlwaysMissingSealedCardRepository())
            shouldThrow<UnmappedCardNamesException> { generator.generate("FDN") }
        }

        test("supportedSets enumerates the known Arena collation sets with real edition names") {
            val sets = SealedPoolGenerator.supportedSets()

            val fdn = sets.first { it.code == "FDN" }
            fdn.name shouldBe "Foundations"
            sets.size shouldBe 16
        }
    })
